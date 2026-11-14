#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
YehoOSS —— 阿里云 OSS 读写（YehoChat 安装包分发用）

凭据（按优先级）
  1. 环境变量 OSS_ACCESS_KEY_ID / OSS_ACCESS_KEY_SECRET
  2. 都没有时回退到 ECS 实例 RAM 角色（走元数据服务取 STS）

  环境变量由 systemd 的 EnvironmentFile 注入：
      /etc/systemd/system/yehoupdate.service
        EnvironmentFile=/etc/yehochat/oss.env        ← 权限必须是 600
  所以 **代码里、仓库里都没有 AK**，密钥只存在于那个 root-only 文件里。

  ⚠️ 别用 ~/.bashrc 存这个 —— systemd 不读 shell 的启动文件，
     os.environ 会是空的，而且这种"明明设了却读不到"最难查。

两个 Endpoint 千万别搞混
  上传用 **内网** oss-cn-hangzhou-internal.aliyuncs.com
      ECS 和 OSS 同地域（cn-hangzhou），走内网不计公网流量费、也快。
  签名给用户下载用 **公网** oss-cn-hangzhou.aliyuncs.com
      用户的手机在公网上，内网地址他们根本连不上。

为什么"签名 URL"不需要任何 OSS 权限就能生成
  sign_url 是纯本地 HMAC 计算，不发起任何 API 调用。
  真正校验权限是在**用户拿这个 URL 去下载的那一刻** —— 那时 OSS 会以
  签名者的身份鉴权，所以这个 AK 必须有 oss:GetObject。

⚠️ 用 ECS RAM 角色时签名 URL 的寿命
  用 STS 临时凭证签出来的 URL，有效期 = min(EXPIRE, 签名时那枚 token 的剩余寿命)。
  用环境变量里的长期 AK 签名没有这个问题（不带 security-token）。
"""

import os

try:
    import oss2
    from oss2.credentials import EcsRamRoleCredentialsProvider

    _OSS_IMPORT_ERROR = None
except ImportError as exc:          # 没装 oss2 时不炸在 import，而是等真正用到再报清楚
    oss2 = None
    EcsRamRoleCredentialsProvider = None
    _OSS_IMPORT_ERROR = exc

BUCKET = "yeho-storage-service-pool"

# 生成给用户下载的签名 URL 用公网地址
ENDPOINT_PUBLIC = "https://oss-cn-hangzhou.aliyuncs.com"
# ECS 上传用内网地址（同地域，不计公网流量）
ENDPOINT_INTERNAL = "https://oss-cn-hangzhou-internal.aliyuncs.com"

PREFIX = "releases/"
EXPIRE = 600          # 签名 URL 有效期（秒）—— 10 分钟

_auth_obj = None
_auth_src = ""


def _auth():
    """全进程共用一个 auth 对象（RAM 角色模式下它自己会缓存并续期 STS）。"""
    global _auth_obj, _auth_src
    if oss2 is None:
        raise RuntimeError(
            "缺少 oss2 依赖，更新服务无法访问 OSS：pip install -r requirements.txt"
            "（原始错误：%s）" % _OSS_IMPORT_ERROR)
    if _auth_obj is None:
        ak = (os.environ.get("OSS_ACCESS_KEY_ID") or "").strip()
        sk = (os.environ.get("OSS_ACCESS_KEY_SECRET") or "").strip()
        if ak and sk:
            _auth_obj, _auth_src = oss2.Auth(ak, sk), "env"
        else:
            # 没配环境变量时才走这条路。auth_host 是 ECS 元数据服务的固定地址，
            # 必须显式传（oss2 2.19 的签名是 __init__(auth_host, max_retries, timeout)）。
            _auth_obj = oss2.ProviderAuth(
                EcsRamRoleCredentialsProvider("http://100.100.100.200",
                                              max_retries=2, timeout=5))
            _auth_src = "ecs-ram-role"
    return _auth_obj


def _bucket(internal):
    return oss2.Bucket(_auth(), ENDPOINT_INTERNAL if internal else ENDPOINT_PUBLIC, BUCKET)


def key_of(filename):
    return PREFIX + filename


def upload(local_path, filename):
    """把本地文件传到 OSS（内网端点）。返回 object key。"""
    k = key_of(filename)
    _bucket(internal=True).put_object_from_file(k, local_path)
    return k


def exists(filename):
    """对象在不在（走内网，不花公网流量）。"""
    try:
        return _bucket(internal=True).object_exists(key_of(filename))
    except Exception:
        return False


def delete(filename):
    try:
        _bucket(internal=True).delete_object(key_of(filename))
        return True
    except Exception:
        return False


def prune(keep=3):
    """
    只保留 releases/ 下最新的 keep 个对象，其余删掉。返回被删的 key 列表。

    为什么要清理：本机那边发布时会把旧包删掉、只留当前一版；但 OSS 是**累积**的，
    不管的话每发一版就多堆一个 2.5MB 的包。留几个历史版本是有用的（新版出问题
    能退回去），但没必要无限堆。

    按 last_modified 排（不是按文件名，文件名里的版本号排序不可靠）。
    """
    b = _bucket(internal=True)
    # ⚠️ 列不出来就**让它抛**，别吞掉返回空列表 —— 那会伪装成"没有需要删的"，
    #    看着一切正常，实际凭据/网络根本没通（这个坑当场踩过一次）。
    #    调用方（发布流程）本来就包在 try 里，抛出去正好进 oss_err。
    items = [(o.last_modified, o.key) for o in oss2.ObjectIterator(b, prefix=PREFIX)]
    items.sort(reverse=True)          # 新的在前
    removed, failed = [], []
    for _, key in items[keep:]:
        try:
            b.delete_object(key)
            removed.append(key)
        except Exception as e:
            failed.append("%s(%s)" % (key, str(e)[:60]))
    if failed:
        # 删不掉也要让人知道，别静默
        raise RuntimeError("部分旧包没删掉: " + "; ".join(failed))
    return removed


def sign(filename, download_name=None):
    """
    生成 EXPIRE 秒有效的 **HTTPS 公网** 签名 URL。

    download_name 传了就带 response-content-disposition，
    浏览器保存下来的文件名会好看（否则是一串带版本号的 key）。
    这个参数也是签名的一部分，oss2 的 params 会一起签进去。
    """
    return sign_raw("GET", key_of(filename), download_name)


# ---------------------------------------------------------------- 通用（按完整 key）
# 网盘服务用这一组：它的对象不在 releases/ 前缀下，自己管 key。

# 预设 PUT 必须**同时**签一个 Content-Type 进去，客户端也得发一模一样的值。
#
# 踩过的坑：只签 URL 不签 Content-Type 时，浏览器/XHR 会自动给 PUT 带上
# Content-Type（比如 application/x-www-form-urlencoded 或文件自身的 MIME），
# 而 OSS 计算签名时会把这个头也算进 StringToSign —— 于是和服务端签出来的
# 对不上，直接 403 SignatureDoesNotMatch。
# GET 不带 Content-Type，所以下载一直没事，只有上传会中招。
PUT_CONTENT_TYPE = "application/octet-stream"


def sign_raw(method, key, download_name=None, expires=EXPIRE, content_type=None):
    """
    按**完整 key** 签名，不套 releases/ 前缀。

    method 传 "GET"（下载）或 "PUT"（浏览器直传）。
    下载时给了 download_name 就带 response-content-disposition 还原原始文件名。
    PUT 时**必须**传 content_type（见上面 PUT_CONTENT_TYPE 的说明）。

    ⚠️ 名字里的非 ASCII 用 RFC 5987 的 filename* 形式带上：
         attachment; filename="fallback"; filename*=UTF-8''%E4%B8%AD%E6%96%87.pdf
       直接在 filename= 里塞中文会构造出非法的 HTTP 头。
    """
    params = None
    if download_name:
        params = {"response-content-disposition":
                  "attachment; " + _disposition_name(download_name)}
    headers = None
    if content_type:
        headers = {"Content-Type": content_type}
    return _bucket(internal=False).sign_url(method, key, expires,
                                            headers=headers, params=params)


def _disposition_name(name):
    """拼 content-disposition 里的文件名部分：纯 ASCII 走 filename=，
    含非 ASCII 再补一个 RFC 5987 的 filename*=。"""
    import urllib.parse
    ascii_name = "".join(ch if 32 <= ord(ch) < 127 and ch not in '"\\' else "_"
                         for ch in (name or "file"))
    out = 'filename="%s"' % ascii_name
    try:
        if name and name != ascii_name:
            out += "; filename*=UTF-8''" + urllib.parse.quote(name, safe="")
    except Exception:
        pass
    return out


def key_exists_raw(key):
    try:
        return _bucket(internal=True).object_exists(key)
    except Exception:
        return False


def delete_raw(key):
    try:
        _bucket(internal=True).delete_object(key)
        return True
    except Exception:
        return False


def size_raw(key):
    """对象大小；查不到返回 -1。"""
    try:
        return _bucket(internal=True).head_object(key).content_length
    except Exception:
        return -1


def self_check(filename=None):
    """
    探活。

    不给 filename：只看凭据配置好没有，**不发任何网络请求**（/ping 走这条，
                   健康检测每轮都打它，不能慢）。
    给了 filename：额外真去 OSS HEAD 一下那个对象（走内网，不花公网流量），
                   这才算验证了"凭据能用 + 桶能访问 + 权限够"。

    ⚠️ 故意**不返回 AK 的任何片段** —— /ping 是公开接口。
    """
    out = {"bucket": BUCKET, "endpoint": ENDPOINT_PUBLIC}
    try:
        # 必须先 _auth() —— _auth_src 是在它里面赋值的。
        # 先拼 out 再调用的话，首次调用会报 source="none"（看着像没读到密钥）。
        cred = _auth().credentials_provider.get_credentials()
        out["source"] = _auth_src or "none"
        out["ok"] = bool(getattr(cred, "access_key_id", ""))
    except Exception as e:
        out["source"] = _auth_src or "none"
        out["ok"] = False
        out["error"] = str(e)[:200]
        return out

    if filename:
        try:
            out["object"] = filename
            out["objectExists"] = _bucket(internal=True).object_exists(key_of(filename))
        except Exception as e:
            out["objectExists"] = False
            out["error"] = str(e)[:200]
    return out
