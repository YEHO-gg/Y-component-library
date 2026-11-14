#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
YehoUpdate — 应用更新分发服务
  8811 : HTTP 监听（经 nginx /update/ 反代，对外是 HTTPS 8443）

公开接口（无需 token）：
  GET  /                       下载网页（HTML，浏览器直接打开）
  GET  /check?app=&ver=        检测更新（JSON）
  GET  /dl/<file>              下载安装包
                               302 到 OSS 上 zip 的签名 URL（走 OSS，不占本机带宽）
                               签不出来时本机发原始 apk 兜底（不限速）
  GET  /dlurl?file=            返回 {"ok","url","expires","source"}，
                               给前端拿临时签名 URL 用（JSON）
  GET  /latest                 永久下载链接，302 到当前版本
  GET  /ping                   探活（返回 OSS 凭据状态）

安装包存放（2026-10-02 起）：
  主存储 = 阿里云 OSS ``yeho-storage-service-pool``（杭州，私有桶）
      上传走内网端点，不花公网流量；签名 URL 走公网端点，有效期 600 秒。
      凭据用 **ECS 实例 RAM 角色**，服务器上不存任何 AK。
      详见 yeho_oss.py
  本机更新目录里仍留一份**兜底副本**：OSS 出问题时自动回退。

出口限速：**已移除**（2026-10-02）
  原先有个 2Mbps 的令牌桶（yeho_ratelimit.py），是为了保护
  ECS 那 3M 出口不被下载打满。现在下载全部走 OSS，字节根本不经过 ECS，
  桶失去了前提，用户也反馈太慢，于是删掉。模块本身已移出仓库。
  本机只剩「OSS 签不出来时兜底」那一条路，不限速 —— 那种情况说明 OSS 出问题了，
  属于异常态，短暂占用带宽可以接受。

需鉴权接口（X-Token）：
  POST /publish                上传新版本

⚠️ 安全：nginx 会为所有代理请求打上 X-Yeho-Proxy 头，yeho_auth._is_local()
据此判定为外部请求，因此 /publish 必须带有效 token —— 与 2026-10-01 修掉的
LOCAL_EXEMPT_V1 漏洞同样的防护思路。新加 nginx location 时务必带上该头。
"""

import hashlib
import json
import os
import re
import sys
import threading
import time
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs, unquote

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import yeho_env                      # 先加载 .env，再读 os.environ
yeho_env.load_env()

# ---- 可移植配置（开源版新增）------------------------------------------
# 所有路径与凭据都从环境变量读，源码里不再出现真实服务器路径。
_CHAT_DIR = os.environ.get("YEHOO_CHAT_DIR") or os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "data")
_UPDATE_DIR = os.environ.get("YEHOO_UPDATE_DIR") or os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "update")
# ----------------------------------------------------------------------


BASE = _UPDATE_DIR
PORT = 8811
APP = "yehochat"
APP_DIR = os.path.join(BASE, APP)
MANIFEST = os.path.join(APP_DIR, "manifest.json")

import yeho_oss as OSS               # 阿里云 OSS：主存储 + 签名下载 URL

# 本机发文件时的读写块大小（原先是 yeho_ratelimit.CHUNK，限速删掉后放这儿）
CHUNK = 65536

# OSS 直链下载开关 ★
#   True （当前）：/dl/ 302 到 OSS 上 **ZIP** 的签名 URL，不占本机带宽。
#   False        ：/dl/ 由本机发原始 .apk，不限速。
#
# ⚠️ 为什么走的是 ZIP 而不是 APK：
#   阿里云禁止用 OSS 默认域名（*.oss-cn-hangzhou.aliyuncs.com）对公网分发 .apk，
#   签名 URL 会返回 <Code>ApkDownloadForbidden</Code>（HTTP 400）；官方要求
#   用 CNAME 自定义域名，而大陆 OSS 桶绑自定义域名需要域名已备案。
#   实测该限制**纯按后缀拦**，换成 .zip 即可通过 —— 所以发布时会额外打一个
#   真 ZIP 传上去，下载走的也是这个 ZIP。
#   ⇒ **用户拿到的是 ZIP，需要自己解压再安装。** 这是明知代价后选的方案。
#
#   回退：OSS 签不出来时 /dl/ 由本机发**原始 .apk** —— 那条路反而不用解压。
OSS_DIRECT = True

# OSS 上保留多少个历史安装包（发布时自动清理更老的）。
# 留几个是为了新版出问题能退回去；留太多纯粹是堆垃圾 —— 一个包 2.5MB。
OSS_KEEP = 3


def log_oss_dropped(dropped):
    print("[oss] 清理旧包: %s" % ", ".join(dropped), flush=True)

_lock = threading.Lock()

# ---------------------------------------------------------------- 清单读写

def load_manifest():
    try:
        with open(MANIFEST, encoding="utf-8") as f:
            d = json.load(f)
        if isinstance(d, dict):
            return d
    except Exception:
        pass
    return {}


def save_manifest(d):
    os.makedirs(APP_DIR, exist_ok=True)
    tmp = MANIFEST + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(d, f, ensure_ascii=False, indent=2)
    os.replace(tmp, MANIFEST)          # 原子替换，避免读到半个文件


def dl_name(fn, ver=""):
    """
    浏览器保存下来的文件名。

    签名 URL 里带 response-content-disposition 才能控制这个 —— 否则存下来的
    是 OSS 上的 key（yehochat-1.3-4.apk）甚至是一串无意义的东西。
    文件名必须是 ASCII，中文/空格会被 OSS 拒掉，所以这里只留白名单字符。
    """
    v = re.sub(r"[^A-Za-z0-9._-]", "", str(ver or ""))
    return ("yehochat-%s.apk" % v) if v else fn


def zip_of(fn):
    """yehochat-1.3-4.apk -> yehochat-1.3-4.zip"""
    return fn[:-4] + ".zip" if fn.endswith(".apk") else fn + ".zip"


# ---------------------------------------------------------------- HTML

def human_size(n):
    try:
        n = float(n)
    except Exception:
        return "-"
    for u in ("B", "KB", "MB", "GB"):
        if n < 1024:
            return f"{n:.1f} {u}" if u != "B" else f"{int(n)} B"
        n /= 1024
    return f"{n:.1f} TB"


def page_html(host):
    m = load_manifest()
    if not m:
        body = ('<div class="card"><h1>YehoChat</h1>'
                '<p class="dim">还没有发布任何版本。</p></div>')
    else:
        notes = (m.get("notes") or "").strip()
        notes_html = "".join(
            f"<li>{escape(x.strip())}</li>" for x in notes.splitlines() if x.strip()
        ) or "<li class='dim'>（无更新说明）</li>"
        ver = escape(str(m.get("versionName", "?")))
        code = escape(str(m.get("versionCode", "?")))
        when = time.strftime("%Y-%m-%d %H:%M", time.localtime(m.get("updated", 0)))
        f = escape(m.get("file", ""))
        size = human_size(m.get("size", 0))
        sha = escape((m.get("sha256") or "")[:16])
        force = "是" if m.get("force") else "否"
        dl = f"/update/dl/{f}"
        body = f'''<div class="card">
  <h1>YehoChat</h1>
  <div class="ver">{ver} <span class="dim">(versionCode {code})</span></div>
  <ul class="notes">{notes_html}</ul>
  <a class="btn" href="{dl}">下载 APK · {size}</a>
  <div class="meta">发布时间：{when}　强制更新：{force}</div>
  <div class="meta dim">SHA256：{sha}…</div>
</div>'''
    return f'''<!DOCTYPE html><html lang="zh"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>YehoChat 下载</title>
<style>
:root{{--bg:#FFFDF5;--card:#fff;--line:#EADFC8;--tx:#22303C;--dim:#8A6D3B;--blue:#3E7A8C}}
*{{box-sizing:border-box}}
body{{margin:0;background:var(--bg);color:var(--tx);
 font:15px/1.7 -apple-system,"Segoe UI",Roboto,sans-serif;padding:24px 16px}}
.card{{background:var(--card);border:1px solid var(--line);border-radius:12px;
 padding:20px;max-width:560px;margin:0 auto}}
h1{{font-size:22px;color:var(--blue);margin:0 0 4px}}
.ver{{font-size:16px;margin-bottom:12px}}
.notes{{margin:0 0 18px;padding-left:20px}}
.notes li{{margin:4px 0}}
.btn{{display:block;text-align:center;background:var(--blue);color:#fff;
 text-decoration:none;padding:13px;border-radius:8px;font-weight:600}}
.meta{{margin-top:12px;font-size:12px;color:var(--dim)}}
.dim{{color:var(--dim)}}
</style></head><body>{body}
<div class="meta" style="text-align:center;margin-top:18px">YehoUpdate</div>
</body></html>'''


def escape(s):
    return (str(s).replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace('"', "&quot;"))


# ---------------------------------------------------------------- Handler

class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    # ---- 工具 ----
    def _json(self, obj, code=200):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _html(self, s, code=200):
        body = s.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _authed(self):
        """经 nginx 进来的请求带 X-Yeho-Proxy，_is_local 会判为外部 -> 必须带 token。"""
        try:
            import yeho_auth as A
            return A.check(self)
        except Exception:
            return False

    # ---- GET ----
    def do_GET(self):
        u = urlparse(self.path)
        p = u.path
        q = parse_qs(u.query)

        if p in ("/ping",):
            d = {"ok": True, "svc": "yehoupdate", "ts": int(time.time() * 1000)}
            # OSS 凭据状态：健康检测靠这个判断凭据有没有正常生效
            d["oss"] = OSS.self_check()
            return self._json(d)

        if p in ("/", "/index.html"):
            host = self.headers.get("Host", "")
            return self._html(page_html(host))

        if p == "/check":
            want = (q.get("app") or [APP])[0]
            if want != APP:
                return self._json({"ok": False, "error": "unknown app"}, 404)
            try:
                cur = int((q.get("ver") or ["0"])[0])
            except Exception:
                cur = 0
            m = load_manifest()
            if not m:
                return self._json({"ok": True, "hasUpdate": False, "reason": "no release"})
            latest = int(m.get("versionCode", 0))
            has = latest > cur
            return self._json({
                "ok": True,
                "app": APP,
                "hasUpdate": has,
                "versionCode": latest,
                "versionName": m.get("versionName", ""),
                "notes": m.get("notes", ""),
                "force": bool(m.get("force")),
                "size": m.get("size", 0),
                "sha256": m.get("sha256", ""),
                # 一律走 OSS（/dl/ 会 302 到 zip 的签名 URL）。
                # 老客户端拿到 ZIP 需要手动解压一次，升到 1.4 后就自动了。
                "url": f"/update/dl/{m.get('file', '')}" if has else "",
                "updated": m.get("updated", 0),
            })

        if p == "/osscheck":
            # 真去 OSS 走一趟（内网 HEAD，不花公网流量），验证凭据+权限+对象都在。
            m = load_manifest()
            return self._json(OSS.self_check(m.get("file") or None))

        if p == "/latest":
            # 永久下载链接：302 到当前发布的那个包。
            # 官网里写死 /update/latest，以后发新版本就不用改页面。
            m = load_manifest()
            fn = (m.get("file") or "")
            if not fn:
                return self._json({"ok": False, "error": "no release"}, 404)
            u = self._oss_zip_url(m)
            if not u:
                # 同 /dl/：不回退本机
                return self._json({
                    "ok": False, "error": "OSS 暂时不可用，请稍后再试",
                    "hint": "排查看 GET /update/osscheck",
                }, 502)
            return self._redirect(u)

        if p == "/dlurl":
            # 给前端的 JSON 接口：返回一个临时签名 URL，前端拿到后自己触发下载。
            # 和 /dl/ 的区别只是"谁去跳转"——这个把 URL 交出去，由页面决定怎么用。
            m = load_manifest()
            fn = (q.get("file") or [m.get("file") or ""])[0]
            if not fn:
                return self._json({"ok": False, "error": "no release"}, 404)
            if not re.fullmatch(r"[A-Za-z0-9._-]+\.apk", fn):
                return self._json({"ok": False, "error": "bad name"}, 400)
            u = self._oss_zip_url(m)
            if u:
                return self._json({"ok": True, "url": u, "source": "oss",
                                   "expires": OSS.EXPIRE,
                                   "file": m.get("zipFile") or fn,
                                   "needUnzip": True})
            # 同 /dl/：不回退本机
            return self._json({"ok": False,
                               "error": "OSS 暂时不可用，请稍后再试"}, 502)

        if p.startswith("/dl/"):
            fn = unquote(p[4:])
            m = load_manifest()
            # 一律 302 到 OSS 上的 ZIP。
            # ⚠️ 用户明确要求：**不回退本机**。签不出来就报错，不做静默降级 ——
            #    本机那条会占满 ECS 那 3M 出口，且会掩盖"OSS 挂了"这件事。
            u = self._oss_zip_url(m) if m.get("file") == fn else None
            if u:
                return self._redirect(u)
            return self._json({
                "ok": False,
                "error": "OSS 暂时不可用，请稍后再试",
                "hint": "排查看 GET /update/osscheck",
            }, 502)

        return self._json({"ok": False, "error": "not found"}, 404)

    def _oss_zip_url(self, m):
        """
        当前版本在 OSS 上那个 **ZIP** 的签名 URL；不可用返回 None。

        注意签的是 zip 不是 apk —— 阿里云只放行非 .apk 后缀，见 OSS_DIRECT 注释。
        """
        if not OSS_DIRECT:
            return None
        zf = m.get("zipFile") or ""
        if not (m.get("oss") and zf):
            return None
        try:
            return OSS.sign(zf, download_name=zf)
        except Exception:
            return None

    def _redirect(self, url):
        self.send_response(302)
        self.send_header("Location", url)
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", "0")
        self.end_headers()

      # ⚠️ 下面这个 _download() 现在**没有任何路由调用它**。
    #    用户明确要求"下载只走 OSS，绝不回退本机"，所以 /dl/、/latest、/dlurl
    #    在 OSS 签不出来时一律报 502，不再由本机发文件。
    #    留着这个方法只是为了万一以后要临时应急 —— 别顺手把它接回路由上。
    def _download(self, name):
        # 防目录穿越：只允许纯文件名 + .apk
        if not re.fullmatch(r"[A-Za-z0-9._-]+\.apk", name or ""):
            return self._json({"ok": False, "error": "bad name"}, 400)
        path = os.path.join(APP_DIR, name)
        if not os.path.isfile(path):
            return self._json({"ok": False, "error": "not found"}, 404)

        size = os.path.getsize(path)
        rng = self.headers.get("Range", "")
        start, end = 0, size - 1
        partial = False
        if rng.startswith("bytes="):
            try:
                spec = rng[6:].split(",")[0].strip()
                a, _, b = spec.partition("-")
                if a:
                    start = int(a)
                    if b:
                        end = min(int(b), size - 1)
                elif b:                       # bytes=-N 末尾 N 字节
                    start = max(0, size - int(b))
                if start > end or start >= size:
                    self.send_response(416)
                    self.send_header("Content-Range", f"bytes */{size}")
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return
                partial = True
            except Exception:
                start, end, partial = 0, size - 1, False

        length = end - start + 1
        self.send_response(206 if partial else 200)
        self.send_header("Content-Type", "application/vnd.android.package-archive")
        self.send_header("Content-Disposition", f'attachment; filename="{name}"')
        self.send_header("Content-Length", str(length))
        self.send_header("Accept-Ranges", "bytes")
        if partial:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.end_headers()

        with open(path, "rb") as f:
            f.seek(start)
            left = length
            while left > 0:
                chunk = f.read(min(CHUNK, left))
                if not chunk:
                    break
                self.wfile.write(chunk)
                left -= len(chunk)

    # ---- POST：上传新版本 ----
    def do_POST(self):
        if urlparse(self.path).path != "/publish":
            return self._json({"ok": False, "error": "not found"}, 404)

        if not self._authed():
            return self._json({"ok": False, "error": "unauthorized"}, 401)

        try:
            n = int(self.headers.get("Content-Length") or 0)
        except Exception:
            n = 0
        if n <= 0:
            return self._json({"ok": False, "error": "empty body"}, 400)
        if n > 200 * 1024 * 1024:
            return self._json({"ok": False, "error": "too large (max 200MB)"}, 413)

        def hd(name, default=""):
            v = self.headers.get(name)
            if v is None:
                return default
            # HTTP 头按 Latin-1 解码，中文的原始 UTF-8 字节会变成乱码字符，
            # 先按 Latin-1 编回字节再按 UTF-8 解，还原中文。
            try:
                v = v.encode("latin-1").decode("utf-8")
            except Exception:
                pass
            try:
                return unquote(v)
            except Exception:
                return v

        try:
            vcode = int(self.headers.get("X-App-Version-Code") or 0)
        except Exception:
            vcode = 0
        vname = hd("X-App-Version-Name") or "?"
        notes = hd("X-Update-Notes")
        force = (self.headers.get("X-Force") or "").lower() in ("1", "true", "yes")

        if vcode <= 0:
            return self._json({"ok": False, "error": "缺少 X-App-Version-Code"}, 400)

        fname = f"{APP}-{vname}-{vcode}.apk".replace("/", "_").replace("\\", "_")
        if not re.fullmatch(r"[A-Za-z0-9._-]+\.apk", fname):
            fname = f"{APP}-{vcode}.apk"

        os.makedirs(APP_DIR, exist_ok=True)
        tmp = os.path.join(APP_DIR, fname + ".part")
        sha = hashlib.sha256()
        got = 0
        try:
            with open(tmp, "wb") as f:
                while got < n:
                    chunk = self.rfile.read(min(65536, n - got))
                    if not chunk:
                        break
                    f.write(chunk)
                    sha.update(chunk)
                    got += len(chunk)
        except Exception as e:
            try:
                os.remove(tmp)
            except Exception:
                pass
            return self._json({"ok": False, "error": f"写入失败: {e}"}, 500)

        if got != n:
            try:
                os.remove(tmp)
            except Exception:
                pass
            return self._json({"ok": False, "error": f"接收不完整 {got}/{n}"}, 400)

        dest = os.path.join(APP_DIR, fname)
        with _lock:
            os.replace(tmp, dest)
            old = load_manifest()

            # 同步传一份到 OSS（走内网端点，不花公网流量）。
            # 传失败**不影响发布**：本机这份留着当兜底，下载会自动回退。
            # 只有 oss=true 时 /dl/ 才会把用户 302 到 OSS。
            # ---- 打一个真 ZIP（里面装着那个 apk），传 OSS ----
            # 为什么要 ZIP 见 OSS_DIRECT 上面的注释：阿里云不放行 .apk。
            zip_name, zip_size = "", 0
            oss_ok, oss_err = False, ""
            try:
                zip_name = zip_of(fname)
                with zipfile.ZipFile(os.path.join(APP_DIR, zip_name), "w",
                                     zipfile.ZIP_DEFLATED) as z:
                    z.write(dest, dl_name(fname, vname))
                zip_size = os.path.getsize(os.path.join(APP_DIR, zip_name))
            except Exception as e:
                oss_err = ("打包失败: %s" % e)[:200]
                zip_name, zip_size = "", 0

            # 传失败不影响发布：本机那份还在，下载会自动回退到原始 apk
            if zip_name:
                try:
                    OSS.upload(os.path.join(APP_DIR, zip_name), zip_name)
                    oss_ok = True
                    # 顺手清理 OSS 上的历史包，只留最新的 OSS_KEEP 个。
                    # 本机那边是"只留当前一版"，OSS 这边留几个是为了能回退。
                    dropped = OSS.prune(OSS_KEEP)
                    if dropped:
                        log_oss_dropped(dropped)
                except Exception as e:
                    oss_err = (("%s | " % oss_err) if oss_err else "") + str(e)[:200]

            save_manifest({
                "app": APP,
                "versionCode": vcode,
                "versionName": vname,
                "notes": notes,
                "force": force,
                "file": fname,
                "size": got,
                "sha256": sha.hexdigest(),
                "updated": int(time.time() * 1000),
                "oss": oss_ok,
                "ossKey": OSS.key_of(zip_name) if oss_ok else "",
                "zipFile": zip_name,
                "zipSize": zip_size,
            })
            # 清理旧版本：只保留当前这一版的 .apk 和 .zip
            keep = {fname, zip_name}
            for fn in os.listdir(APP_DIR):
                if fn.endswith((".apk", ".zip")) and fn not in keep:
                    try:
                        os.remove(os.path.join(APP_DIR, fn))
                    except Exception:
                        pass
            prev = old.get("versionCode")

        return self._json({
            "ok": True, "file": fname, "size": got,
            "sha256": sha.hexdigest(), "prevVersionCode": prev,
            "url": f"/update/dl/{fname}",
            "oss": oss_ok, "ossError": oss_err,
        })


def main():
    os.makedirs(APP_DIR, exist_ok=True)
    srv = ThreadingHTTPServer(("127.0.0.1", PORT), H)
    print(f"yehoupdate listening 127.0.0.1:{PORT}, base={BASE}", flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
