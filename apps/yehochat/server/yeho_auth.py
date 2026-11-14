#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
YehoAuth - 统一鉴权模块（被各服务 import）

核心设计：
  - 脚本内**不含任何 token / 密钥明文**
  - 全部读外部配置：auth.json（权限建议 600）
  - 存盘内容**全是不可逆哈希**，服务端永远拿不回明文
  - 两个身份：
      master(主控, 100位) → 只用于「重置日常 token」
      day(日常, 50位)     → 各业务服务鉴权用
  - 重置流程：主控校验通过 → 生成新日常明文 → **只在响应里回传一次**
              → 服务端只落 SHA-256(盐+日常) 哈希
  - 之后验证日常 token：SHA256(盐+输入) == 存的哈希

auth.json 结构：
  {
    "ver": 3,
    "algo": "sha256",
    "salt": "<32 hex 随机盐>",
    "master_sha": "<sha256(salt+'master:'+master)>",   # 校验主控
    "day_sha":    "<sha256(salt+'day:'+day)>",         # 校验日常
    "updated": <ms>
  }

对外函数（保持旧名，11 个服务免改）：
  check(handler) / check_token(tok) / verify(handler)
  verify_master(m) / reveal(m) / reset_token(m)
  change_token(old,new) / ensure_init() / status() / init_conf(...)
"""

import hashlib
import hmac
import json
import os
import secrets
import string
import time
from urllib.parse import unquote

CONF_FILE = os.environ.get("YEHOAUTH_CONF") or os.path.join(os.path.dirname(os.path.abspath(__file__)), "data", "auth.json")
MASTER_LEN = 100
DAY_LEN = 50


class AuthConfigError(RuntimeError):
    """配置缺失或损坏。"""


# ---------- 基础 ----------
def _sha(salt, kind, tok):
    """统一哈希：sha256(salt + ':' + kind + ':' + token)。"""
    raw = "%s:%s:%s" % (salt, kind, tok)
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


def _rand_token(n, alphabet=None):
    """生成 n 位随机 token（字母+数字+特殊字符）。"""
    if alphabet is None:
        alphabet = string.ascii_letters + string.digits + "!@#$%^&*()-_=+[]{}:,.?"
    return "".join(secrets.choice(alphabet) for _ in range(n))


def _read_conf():
    if not os.path.exists(CONF_FILE):
        raise AuthConfigError("auth config not found: %s" % CONF_FILE)
    try:
        with open(CONF_FILE, "r", encoding="utf-8") as f:
            conf = json.load(f)
    except Exception as e:
        raise AuthConfigError("auth config broken: %s" % e)
    for k in ("salt", "master_sha", "day_sha"):
        if k not in conf or not conf[k]:
            raise AuthConfigError("auth config missing field: %s" % k)
    return conf


def _write_conf(conf):
    tmp = CONF_FILE + ".tmp"
    os.makedirs(os.path.dirname(CONF_FILE), exist_ok=True)
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(conf, f)
    os.replace(tmp, CONF_FILE)
    try:
        os.chmod(CONF_FILE, 0o600)
    except Exception:
        pass


# ---------- 初始化 / 运维 ----------
def init_conf(master=None, day=None, algo="sha256", force=False):
    """
    运维初始化。不传 master 则**自动生成**一个100位主控。
    返回 (True, {"master_token":..., "day_token":...}) —— 明文仅此一次。
    """
    if os.path.exists(CONF_FILE) and not force:
        return False, "config exists; use force=True"
    if master is None:
        master = _rand_token(MASTER_LEN)
    elif len(master) < 16:
        return False, "master too short"
    if day is None:
        day = _rand_token(DAY_LEN)
    salt = secrets.token_hex(16)
    conf = {
        "ver": 3,
        "algo": algo,
        "salt": salt,
        "master_sha": _sha(salt, "master", master),
        "day_sha": _sha(salt, "day", day),
        "updated": int(time.time() * 1000),
    }
    _write_conf(conf)
    return True, {"ok": True, "master_token": master, "day_token": day}


def ensure_init():
    """兼容旧调用：只检查配置健康，绝不写盘。"""
    try:
        _read_conf()
        return True
    except AuthConfigError:
        return False


def status():
    try:
        conf = _read_conf()
        return True, {"ok": True, "ver": conf.get("ver"), "algo": conf.get("algo"),
                      "file": CONF_FILE, "updated": conf.get("updated")}
    except AuthConfigError as e:
        return False, {"ok": False, "error": str(e), "file": CONF_FILE}


# ---------- 校验 ----------
def _is_local(handler):
    """LOCAL_EXEMPT_V1: 本机回环来源 -> 豁免鉴权（服务器内部服务互调）

    ⚠️ 安全修正 (PROXY_GUARD_V1)：经 nginx 反代进来的外部请求，源地址同样
    是 127.0.0.1，与真正的服务内部互调无法靠源地址区分。nginx 已为所有
    location 打上 X-Yeho-Proxy 头（proxy_set_header 会覆盖客户端伪造值），
    因此见到该头一律判定为外部请求，不再豁免。
    """
    try:
        if handler is not None and handler.headers.get("X-Yeho-Proxy"):
            return False
        host = (handler.client_address[0] or "") if handler else ""
    except Exception:
        return False
    return host in ("127.0.0.1", "::1", "localhost")


def check_token(tok):
    """校验日常 token（fail closed）。"""
    if not tok:
        return False
    try:
        conf = _read_conf()
    except AuthConfigError:
        return False
    calc = _sha(conf["salt"], "day", tok)
    return hmac.compare_digest(calc, conf["day_sha"])


def verify_master(tok):
    """校验主控 token。"""
    if not tok:
        return False
    try:
        conf = _read_conf()
    except AuthConfigError:
        return False
    calc = _sha(conf["salt"], "master", tok)
    return hmac.compare_digest(calc, conf["master_sha"])


def _token_from_request(handler):
    tok = handler.headers.get("X-Token", "") if handler else ""
    if not tok:
        try:
            path = getattr(handler, "path", "")
            qs = path.split("?", 1)[1] if "?" in path else ""
            for kv in qs.split("&"):
                if kv.startswith("token="):
                    tok = kv[6:]
                    break
        except Exception:
            tok = ""
    return tok


def check(handler):
    """各服务调用：校验日常 token（X-Token 头 或 ?token=）。"""
    if _is_local(handler):
        return True
    tok = _token_from_request(handler)
    if check_token(tok):
        return True
    # 兼容 URL 编码过的 token。
    # 日常 token 里有 & # + % = 这些会破坏查询串的字符（&会截断参数、+会变空格），
    # 所以浏览器/App 拼网址时必须编码，服务端这里就得解码回去。
    # 先试原文、再试解码后的 —— 两种都认，发原文的老客户端不受影响。
    try:
        d = unquote(tok)
        if d != tok and check_token(d):
            return True
    except Exception:
        pass
    return False


def verify(handler):
    if check(handler):
        return True, None
    return False, {"ok": False, "error": "unauthorized",
                   "ts": int(time.time() * 1000)}


# ---------- 重置流程 ----------
def reveal(master):
    """
    旧接口兼容：现在服务端不存明文，无法 reveal。
    返回 None（提示调用方改用 reset_token）。
    """
    return None


def reset_token(master):
    """
    主控校验通过 → 生成新日常 token → 写回哈希 → 返回明文（仅此一次）。
    返回 (ok, {"ok":True,"day_token":新明文} / "master invalid")
    """
    if not verify_master(master):
        return False, "master invalid"
    new_day = _rand_token(DAY_LEN)
    conf = _read_conf()
    conf["day_sha"] = _sha(conf["salt"], "day", new_day)
    conf["updated"] = int(time.time() * 1000)
    _write_conf(conf)
    return True, {"ok": True, "day_token": new_day,
                  "ts": int(time.time() * 1000)}


def change_token(old_plain, new_plain):
    """兼容旧接口：日常 token 换日常 token。"""
    if not check_token(old_plain):
        return False, "old token invalid"
    if not new_plain or len(new_plain) < 8:
        return False, "new token too short (>=8)"
    try:
        conf = _read_conf()
    except AuthConfigError:
        return False, "config missing"
    conf["day_sha"] = _sha(conf["salt"], "day", new_plain)
    conf["updated"] = int(time.time() * 1000)
    _write_conf(conf)
    return True, "ok"


if __name__ == "__main__":
    ok, info = status()
    print("status:", ok, info)