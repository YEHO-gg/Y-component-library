#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
YehoChat 用户服务 (HTTP :8706)
- 注册：邮箱 + 密码 -> 发 4 位验证码 -> 校验建号
- 登录：账号(UUID或邮箱)+密码  /  账号+邮箱验证码  -> 发登录K
- 用户自助：改显示名 / 改UUID(30天限一次)
- 管理：用户列表 / 搜索 / 封禁 / 解封 / 删除 / 强制改
- 鉴权：所有敏感操作校验登录K（X-Chat-Token）
"""
import io, os, re, ssl, json, time, hmac, random, string, hashlib, sqlite3, smtplib, threading, sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs
from email.mime.text import MIMEText

# ---- 可移植配置（开源版新增）------------------------------------------
# 所有路径与凭据都从环境变量读，源码里不再出现真实服务器路径。
_CHAT_DIR = os.environ.get("YEHOO_CHAT_DIR") or os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "data")
_UPDATE_DIR = os.environ.get("YEHOO_UPDATE_DIR") or os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "update")
# ----------------------------------------------------------------------


PORT = 8706
BASE = os.path.dirname(os.path.abspath(__file__))
DB = os.path.join(BASE, "chat.db")
LOG_FILE = os.path.join(BASE, "users.log")

# ---- 163 邮箱 ----
SMTP_HOST = os.environ.get("YEHOO_SMTP_HOST", "smtp.example.com")
SMTP_PORT = int(os.environ.get("YEHOO_SMTP_PORT", "465"))
SMTP_USER = os.environ.get("YEHOO_SMTP_USER", "")
SMTP_PASS = os.environ.get("YEHOO_SMTP_PASS", "")
SMTP_FROM = os.environ.get("YEHOO_SMTP_FROM") or SMTP_USER

# ---- 参数 ----
CODE_TTL = 300           # 验证码 5 分钟有效
LOGIN_K_TTL = 10 * 86400 # 登录K 10 天未在线即失效
TOKEN_TTL_DAYS = 30
UUID_CHANGE_INTERVAL = 30 * 86400
MAX_CODE_TRY = 5
NAME_RE = re.compile(r"^[A-Za-z0-9\u4e00-\u9fff]{1,20}$")
UUID_RE = re.compile(r"^[A-Za-z0-9_]{1,20}$")
EMAIL_RE = re.compile(r"^[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}$")

# ---- 日志 ----
import logging
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
try:
    import yeho_auth
except Exception:
    yeho_auth = None
logging.basicConfig(level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    handlers=[logging.FileHandler(LOG_FILE), logging.StreamHandler()])
log = logging.getLogger("chat_users")

_lock = threading.Lock()
_pending = {}   # email -> {code, exp, tries}


def db():
    c = sqlite3.connect(DB, timeout=10)
    c.row_factory = sqlite3.Row
    return c


def db_init():
    c = db()
    c.execute("""CREATE TABLE IF NOT EXISTS users (
        uid       INTEGER PRIMARY KEY AUTOINCREMENT,
        uuid      TEXT UNIQUE,
        name      TEXT,
        email     TEXT UNIQUE,
        pw_salt   TEXT,
        pw_hash   TEXT,
        banned    INTEGER NOT NULL DEFAULT 0,
        uuid_ts   INTEGER NOT NULL DEFAULT 0,
        last_ts   INTEGER NOT NULL DEFAULT 0,
        created   INTEGER NOT NULL
    )""")
    c.execute("""CREATE TABLE IF NOT EXISTS sessions (
        token     TEXT PRIMARY KEY,
        uid       INTEGER NOT NULL,
        last_ts   INTEGER NOT NULL,
        created   INTEGER NOT NULL
    )""")
    # archived: 归档（软删除）。归档的账号不出现在用户列表、不能登录，
    # 名册里会被挂进 archived 名单让客户端显示成「（用户已注销）」；
    # 但账号行、消息、引用一律不动，随时可以恢复。
    have = [r[1] for r in c.execute("PRAGMA table_info(users)")]
    if "archived" not in have:
        c.execute("ALTER TABLE users ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
        log.info("users 表补上 archived 列")
    c.commit()
    c.close()


def hash_pw(pw, salt):
    """PBKDF2-HMAC-SHA256, 12 万次迭代"""
    dk = hashlib.pbkdf2_hmac("sha256", pw.encode("utf-8"),
                             salt.encode("utf-8"), 120000)
    return dk.hex()


def default_uuid(email):
    """user_ + 邮箱把@换成大写D，仅字母数字，超20截断"""
    base = email.replace("@", "D")
    base = re.sub(r"[^A-Za-z0-9]", "", base)
    s = "user_" + base
    if len(s) > 20:
        s = s[:20]
    return s


UID_SEQ_FILE = os.path.join(BASE, "uid_seq")


def _read_seq():
    try:
        with io.open(UID_SEQ_FILE, "r", encoding="utf-8") as f:
            return int((f.read() or "0").strip() or 0)
    except Exception:
        return 0


def _max_gone_uid():
    """名册文件里记着的、已注销的最大 uid。"""
    try:
        if not os.path.exists(ROSTER_FILE):
            return 0
        with io.open(ROSTER_FILE, "r", encoding="utf-8") as f:
            d = json.load(f) or {}
        if isinstance(d, dict) and ("users" in d or "deleted" in d):
            gone = d.get("deleted") or []
        else:
            gone = []
        return max([int(x) for x in gone] or [0])
    except Exception:
        return 0


def _max_msg_uid(c):
    """消息表里还挂着的最大 uid（已注销的会被改成 -1，不影响 MAX）。"""
    try:
        return int(c.execute("SELECT COALESCE(MAX(uid),0) FROM msgs").fetchone()[0] or 0)
    except sqlite3.OperationalError:
        return 0        # 还没有 msgs 表


def next_uid(c):
    """发一个新 uid：历史最高 + 1。只增不减，注销掉的号不回收。

    为什么不只看 users 表的 max(uid)：把最大的那个号注销掉之后 max 会退回去，
    下一个注册者就会拿到同一个号 —— 而那批老消息还挂在那个号上，就会"附身"到新人身上。

    所以取四个来源的最大值，任一个还在就挡得住：
      · uid_seq 文件（正常路径，唯一权威的"发到几号了"）
      · users 表的 max(uid)
      · msgs 表里还挂着的最大 uid
      · roster.json 里已注销名单的最大 uid
    这样即使 uid_seq 文件被误删，也不会把某个注销过的号重新发出去。
    """
    mx = max(_read_seq(),
             int(c.execute("SELECT COALESCE(MAX(uid),0) FROM users").fetchone()[0] or 0),
             _max_msg_uid(c),
             _max_gone_uid())
    nxt = mx + 1
    try:
        tmp = UID_SEQ_FILE + ".tmp"
        with io.open(tmp, "w", encoding="utf-8") as f:
            f.write(str(nxt))
        os.replace(tmp, UID_SEQ_FILE)      # 原子替换
    except Exception as e:
        log.info("uid_seq write err: %s", e)
    return nxt


def make_uuid(c, email):
    """生成不重复的默认 UUID。
    基础版撞了就逐次砍掉基础尾部 2 字符、补随机字符；
    多次仍撞则退化为纯随机 user_+12。"""
    base = email.replace("@", "D")
    base = re.sub(r"[^A-Za-z0-9]", "", base)
    cand = ("user_" + base)[:20]

    def taken(u):
        return c.execute("SELECT 1 FROM users WHERE uuid=?", (u,)).fetchone() is not None

    if not taken(cand):
        return cand
    # 梯度重试：每次把候选尾部砍短 2 个字符，再接随机字符（总长保持 <=20）
    for cut in range(2, 14, 2):
        core = cand[:-cut] if len(cand) > cut else "user_"
        if len(core) < 5:
            core = "user_"
        for _ in range(6):
            tail = rand_name(2)
            u = (core + tail)[:20]
            if len(u) < 5:
                u = "user_" + rand_name(12)
            if not taken(u):
                return u
    # 兜底：纯随机
    while True:
        u = "user_" + rand_name(12)
        if not taken(u):
            return u


def rand_name(n=6):
    return "".join(random.choice(string.ascii_letters + string.digits) for _ in range(n))



def make_name(c, n=6):
    """随机 6 位名字，撞了就加长/换，最多试 50 次"""
    for i in range(50):
        nm = rand_name(n + i // 10)
        if not c.execute("SELECT 1 FROM users WHERE name=?", (nm,)).fetchone():
            return nm
    return "u" + rand_token(10)

def rand_token(n=48):
    return "".join(random.choice(string.ascii_letters + string.digits) for _ in range(n))


def now():
    return int(time.time())


# ---------------- 邮件 ----------------
def send_code_mail(to_email, code):
    body = ("YehoChat 注册验证码\n\n"
            "你的验证码是：%s\n\n"
            "%d 分钟内有效，请勿告诉他人。\n"
            "如果这不是你本人操作，请忽略本邮件。\n") % (code, CODE_TTL // 60)
    m = MIMEText(body, "plain", "utf-8")
    m["Subject"] = "YehoChat 注册验证码"
    m["From"] = SMTP_FROM
    m["To"] = to_email
    ctx = ssl.create_default_context()
    with smtplib.SMTP_SSL(SMTP_HOST, SMTP_PORT, context=ctx, timeout=15) as s:
        s.login(SMTP_USER, SMTP_PASS)
        s.sendmail(SMTP_FROM, [to_email], m.as_string())


# ---------------- 业务 ----------------
def do_send_code(email):
    if not EMAIL_RE.match(email or ""):
        return 400, {"ok": False, "error": "邮箱格式不对"}
    code = "%04d" % random.randint(0, 9999)
    _pending[email] = {"code": code, "exp": now() + CODE_TTL, "tries": 0}
    try:
        send_code_mail(email, code)
    except Exception as e:
        log.info("mail err: %s", e)
        return 500, {"ok": False, "error": "验证码发送失败，请稍后再试"}
    log.info("code sent to %s", email)
    return 200, {"ok": True, "msg": "验证码已发送"}


def do_register(email, pw, pw2, code):
    if not EMAIL_RE.match(email or ""):
        return 400, {"ok": False, "error": "邮箱格式不对"}
    if not isinstance(pw, str) or len(pw) < 6:
        return 400, {"ok": False, "error": "密码至少 6 位"}
    if pw != pw2:
        return 400, {"ok": False, "error": "两次密码不一致"}
    p = _pending.get(email)
    if not p or p["exp"] < now():
        return 400, {"ok": False, "error": "验证码已过期，请重新获取"}
    if p["tries"] >= MAX_CODE_TRY:
        return 400, {"ok": False, "error": "错误次数太多，请重新获取"}
    if str(code) != p["code"]:
        p["tries"] += 1
        return 400, {"ok": False, "error": "验证码不对"}
    with _lock:
        c = db()
        try:
            if c.execute("SELECT 1 FROM users WHERE email=?", (email,)).fetchone():
                c.close()
                return 400, {"ok": False, "error": "这个邮箱已经注册过了"}
            uuid = make_uuid(c, email)
            salt = rand_token(16)
            name = make_name(c)
            # uid 只增不复用：注销一个号，它的号就永久作废，绝不发给后来的人。
            # 原先这里是"补最小空位"，删掉谁下一个注册者就拿到谁 —— 而消息是按 uid
            # 归属的，那批老消息就会顶着新人的名字显示。
            uid = next_uid(c)
            c.execute("INSERT INTO users (uid,uuid,name,email,pw_salt,pw_hash,created) VALUES (?,?,?,?,?,?,?)",
                      (uid, uuid, name, email, salt, hash_pw(pw, salt), now()))
            c.commit()
            row = c.execute("SELECT uid,uuid,name FROM users WHERE email=?", (email,)).fetchone()
            c.close()
            _pending.pop(email, None)
            log.info("register: %s uid=%d uuid=%s", email, row["uid"], row["uuid"])
            return 200, {"ok": True, "uid": row["uid"], "uuid": row["uuid"], "name": row["name"]}
        except Exception as e:
            c.close()
            log.info("register err: %s", e)
            return 500, {"ok": False, "error": "注册失败"}


def find_account(acc):
    """账号自动判断：邮箱 or UUID"""
    c = db()
    row = None
    if "@" in (acc or ""):
        row = c.execute("SELECT * FROM users WHERE email=?", (acc,)).fetchone()
    else:
        row = c.execute("SELECT * FROM users WHERE uuid=?", (acc,)).fetchone()
    c.close()
    return row


def new_session(uid):
    tk = rand_token(48)
    c = db()
    # 单端登录：作废该用户之前的全部 session，保证只有最新一个有效
    c.execute("DELETE FROM sessions WHERE uid=?", (uid,))
    c.execute("INSERT INTO sessions (token,uid,last_ts,created) VALUES (?,?,?,?)",
              (tk, uid, now(), now()))
    c.commit()
    c.close()
    return tk


def check_token(tk):
    """校验登录K，返回 uid 或 None。

    ⚠️ 不能只看"session 行还在不在"：被封禁 / 归档的账号，只要那行没被删掉，
       照样能改名、改 uuid。所以账号状态一并验。
       （封禁和归档本来就会删 session，这里是防"哪条路径漏了删"的兜底 ——
       登录态校验就该是双保险，而不是指望调用方每次都记得清 token。）
    """
    if not tk:
        return None
    c = db()
    row = c.execute("SELECT * FROM sessions WHERE token=?", (tk,)).fetchone()
    if not row:
        c.close()
        return None
    if now() - row["last_ts"] > LOGIN_K_TTL:
        c.close()
        return None
    uid = row["uid"]
    u = c.execute("SELECT banned,archived FROM users WHERE uid=?", (uid,)).fetchone()
    c.close()
    if not u:
        return None
    if int(u["banned"] or 0) == 1:
        return None
    if _is_archived(u):
        return None
    return uid


def touch_session(tk):
    c = db()
    c.execute("UPDATE sessions SET last_ts=? WHERE token=?", (now(), tk))
    c.commit()
    c.close()


def _is_archived(row):
    """这个账号是不是归档了。老库还没迁移出 archived 列时当作没归档。"""
    try:
        return int(row["archived"] or 0) == 1
    except Exception:
        return False


def do_login_pw(acc, pw):
    row = find_account(acc)
    if not row:
        return 400, {"ok": False, "error": "账号或密码不对"}
    if _is_archived(row):
        return 403, {"ok": False, "error": "账号已归档，无法登录"}
    if row["banned"]:
        return 403, {"ok": False, "error": "账号已被封禁"}
    if hash_pw(pw or "", row["pw_salt"]) != row["pw_hash"]:
        return 400, {"ok": False, "error": "账号或密码不对"}
    tk = new_session(row["uid"])
    c = db(); c.execute("UPDATE users SET last_ts=? WHERE uid=?", (now(), row["uid"])); c.commit(); c.close()
    return 200, {"ok": True, "token": tk, "uid": row["uid"], "uuid": row["uuid"], "name": row["name"]}


def do_login_code(acc, code):
    row = find_account(acc)
    if not row:
        return 400, {"ok": False, "error": "账号不存在"}
    if _is_archived(row):
        return 403, {"ok": False, "error": "账号已归档，无法登录"}
    if row["banned"]:
        return 403, {"ok": False, "error": "账号已被封禁"}
    email = row["email"]
    p = _pending.get(email)
    if not p or p["exp"] < now():
        return 400, {"ok": False, "error": "验证码已过期"}
    if str(code) != p["code"]:
        p["tries"] += 1
        return 400, {"ok": False, "error": "验证码不对"}
    _pending.pop(email, None)
    tk = new_session(row["uid"])
    c = db(); c.execute("UPDATE users SET last_ts=? WHERE uid=?", (now(), row["uid"])); c.commit(); c.close()
    return 200, {"ok": True, "token": tk, "uid": row["uid"], "uuid": row["uuid"], "name": row["name"]}


def do_login_send_code(acc):
    row = find_account(acc)
    if not row:
        return 400, {"ok": False, "error": "账号不存在"}
    if _is_archived(row):
        return 403, {"ok": False, "error": "账号已归档，无法登录"}
    if row["banned"]:
        return 403, {"ok": False, "error": "账号已被封禁"}
    return do_send_code(row["email"])


def do_profile(uid):
    c = db()
    row = c.execute("SELECT uid,uuid,name,email,banned,uuid_ts,created FROM users WHERE uid=?", (uid,)).fetchone()
    c.close()
    if not row:
        return 404, {"ok": False, "error": "用户不存在"}
    d = dict(row)
    d["ok"] = True
    d["uuid_left"] = 0
    if d["uuid_ts"]:
        left = UUID_CHANGE_INTERVAL - (now() - d["uuid_ts"])
        d["uuid_left"] = max(0, left)
    return 200, d


def do_rename(uid, name):
    if not NAME_RE.match(name or ""):
        return 400, {"ok": False, "error": "名字只能 1-20 位中英文/数字"}
    c = db()
    if c.execute("SELECT 1 FROM users WHERE name=? AND uid<>?", (name, uid)).fetchone():
        c.close()
        return 400, {"ok": False, "error": "这个名字已被占用"}
    c.execute("UPDATE users SET name=? WHERE uid=?", (name, uid))
    c.commit()
    c.close()
    return 200, {"ok": True, "name": name}


def do_set_uuid(uid, uuid):
    if not UUID_RE.match(uuid or ""):
        return 400, {"ok": False, "error": "UUID 只能字母、数字、下划线，不超过 20 位"}
    c = db()
    row = c.execute("SELECT uuid_ts FROM users WHERE uid=?", (uid,)).fetchone()
    if row and row["uuid_ts"]:
        left = UUID_CHANGE_INTERVAL - (now() - row["uuid_ts"])
        if left > 0:
            c.close()
            return 400, {"ok": False, "error": "UUID 每 30 天只能改一次，还需 %d 天" % ((left + 86399) // 86400)}
    if c.execute("SELECT 1 FROM users WHERE uuid=? AND uid<>?", (uuid, uid)).fetchone():
        c.close()
        return 400, {"ok": False, "error": "这个 UUID 已被占用"}
    c.execute("UPDATE users SET uuid=?, uuid_ts=? WHERE uid=?", (uuid, now(), uid))
    c.commit()
    c.close()
    return 200, {"ok": True, "uuid": uuid}


def do_reset_send_code(acc):
    """忘记密码：传 uuid/邮箱 -> 给该账号邮箱发码"""
    row = find_account(acc)
    if not row:
        return 400, {"ok": False, "error": "账号不存在"}
    if row["banned"]:
        return 403, {"ok": False, "error": "账号已被封禁"}
    return do_send_code(row["email"])


def do_reset_password(acc, code, pw, pw2):
    """忘记密码：账号 + 码 + 新密码 -> 改"""
    if not isinstance(pw, str) or len(pw) < 6:
        return 400, {"ok": False, "error": "密码至少 6 位"}
    if pw != pw2:
        return 400, {"ok": False, "error": "两次密码不一致"}
    row = find_account(acc)
    if not row:
        return 400, {"ok": False, "error": "账号不存在"}
    if row["banned"]:
        return 403, {"ok": False, "error": "账号已被封禁"}
    email = row["email"]
    p = _pending.get(email)
    if not p or p["exp"] < now():
        return 400, {"ok": False, "error": "验证码已过期，请重新获取"}
    if p["tries"] >= MAX_CODE_TRY:
        return 400, {"ok": False, "error": "错误次数太多，请重新获取"}
    if str(code) != p["code"]:
        p["tries"] += 1
        return 400, {"ok": False, "error": "验证码不对"}
    _pending.pop(email, None)
    salt = rand_token(16)
    c = db()
    c.execute("UPDATE users SET pw_salt=?, pw_hash=? WHERE uid=?",
              (salt, hash_pw(pw, salt), row["uid"]))
    # 改密后把该用户所有会话踢掉
    c.execute("DELETE FROM sessions WHERE uid=?", (row["uid"],))
    c.commit()
    c.close()
    revoke_push(row["uid"], "kick")     # 改密了就把在线的那条连接立刻断掉
    log.info("password reset: uid=%d", row["uid"])
    return 200, {"ok": True, "msg": "密码已重置"}


def do_change_password(uid, code, pw, pw2):
    """已登录改密码：也要邮箱验证"""
    if not isinstance(pw, str) or len(pw) < 6:
        return 400, {"ok": False, "error": "密码至少 6 位"}
    if pw != pw2:
        return 400, {"ok": False, "error": "两次密码不一致"}
    c = db()
    row = c.execute("SELECT uid,email FROM users WHERE uid=?", (uid,)).fetchone()
    c.close()
    if not row:
        return 404, {"ok": False, "error": "用户不存在"}
    email = row["email"]
    p = _pending.get(email)
    if not p or p["exp"] < now():
        return 400, {"ok": False, "error": "验证码已过期，请重新获取"}
    if str(code) != p["code"]:
        p["tries"] += 1
        return 400, {"ok": False, "error": "验证码不对"}
    _pending.pop(email, None)
    salt = rand_token(16)
    c = db()
    c.execute("UPDATE users SET pw_salt=?, pw_hash=? WHERE uid=?",
              (salt, hash_pw(pw, salt), uid))
    c.execute("DELETE FROM sessions WHERE uid=?", (uid,))
    c.commit()
    c.close()
    revoke_push(uid, "kick")            # 改密了就把在线的那条连接立刻断掉
    log.info("password changed: uid=%d", uid)
    return 200, {"ok": True, "msg": "密码已修改，请重新登录"}


# ---------------- 管理 ----------------
def admin_list(q="", archived=False):
    """archived=False 只列在用账号（归档的不出现在用户列表）；True 只列归档账号。"""
    cols = "uid,uuid,name,email,banned,last_ts,created,archived"
    where = "archived=1" if archived else "archived=0"
    c = db()
    if q:
        rows = c.execute("SELECT " + cols + " FROM users WHERE " + where
                         + " AND (uuid LIKE ? OR name LIKE ? OR email LIKE ?) ORDER BY uid",
                         ("%" + q + "%", "%" + q + "%", "%" + q + "%")).fetchall()
    else:
        rows = c.execute("SELECT " + cols + " FROM users WHERE " + where
                         + " ORDER BY uid").fetchall()
    c.close()
    return 200, {"ok": True, "users": [dict(r) for r in rows]}


ROSTER_FILE = os.path.join(_CHAT_DIR, "roster.json")


def _roster_split(d):
    """兼容三种名册格式：
        v1（旧）: {"1": "YEHO"}                                    —— 只有昵称
        v2:       {"users": {...}, "deleted": [3]}                 —— 记下硬删掉的 uid
        v3:       {"users": {...}, "deleted": [3], "archived": [4]} —— 再加归档掉的 uid

    deleted 和 archived 对客户端的显示效果是一样的（都挂「（用户已注销）」），
    分开记是因为**归档要能撤销**：恢复时把 uid 从 archived 移出去就行，
    而 deleted 是硬删，撤销没有意义。
    """
    if isinstance(d, dict) and ("users" in d or "deleted" in d or "archived" in d):
        return (dict(d.get("users") or {}),
                list(d.get("deleted") or []),
                list(d.get("archived") or []))
    return dict(d or {}), [], []


def roster_mark_gone(uid):
    """把已注销的账号从名册里摘掉，并记进 deleted 名单。

    为什么要记 deleted：别人客户端缓存里那些老消息还挂着这个 uid，server.py 广播
    这份名单后，客户端才能把它们就地标成"已注销"，而不是继续拿名册里那条旧昵称显示。
    """
    try:
        users, deleted, archived = _roster_read()
        users.pop(str(uid), None)
        if int(uid) not in deleted:
            deleted.append(int(uid))
        # 已经是硬删了，归档标记就没意义了（留着的话恢复名单会对不上）
        if int(uid) in archived:
            archived.remove(int(uid))
        _roster_write(users, deleted, archived)
        log.info("roster mark gone: uid=%s", uid)
    except Exception as e:
        log.info("roster mark gone err: %s", e)


def _roster_read():
    """读名册 -> (users, deleted, archived)。文件不在/坏了都当空名册。"""
    d = {}
    if os.path.exists(ROSTER_FILE):
        with io.open(ROSTER_FILE, "r", encoding="utf-8") as f:
            d = json.load(f) or {}
    return _roster_split(d)


def _roster_write(users, deleted, archived):
    """原子写回。server.py 那边每 10 秒看一次 mtime，一变就广播给所有在线端。"""
    tmp = ROSTER_FILE + ".tmp"
    with io.open(tmp, "w", encoding="utf-8") as f:
        json.dump({"users": users, "deleted": deleted, "archived": archived},
                  f, ensure_ascii=False)
    os.replace(tmp, ROSTER_FILE)


def roster_mark_archived(uid, on):
    """归档 / 取消归档：只动名册里的 archived 名单，一个消息字段都不碰。

    客户端渲染名字时查这份名单，命中就挂「（用户已注销）」。所以恢复的时候
    把 uid 移出去，历史消息立刻还原 —— 换成 del 那种把 msgs.uid 改成 -1 的
    写法就回不来了，因为原来的 uid 已经丢了。
    """
    try:
        users, deleted, archived = _roster_read()
        u = int(uid)
        if on:
            users.pop(str(u), None)          # 从在用名册里摘掉
            if u not in archived:
                archived.append(u)
        else:
            if u in archived:
                archived.remove(u)
            # 名字放回名册（真名以 DB 为准，server.py 下次同步会覆盖）
            c = db()
            row = c.execute("SELECT name FROM users WHERE uid=?", (u,)).fetchone()
            c.close()
            if row:
                users[str(u)] = row["name"]
        _roster_write(users, deleted, archived)
        log.info("roster archive %s: uid=%s", "on" if on else "off", uid)
    except Exception as e:
        log.info("roster archive err: %s", e)


REVOKE_FILE = os.path.join(_CHAT_DIR, "revoke.json")
_REVOKE_LOCK = threading.Lock()


def revoke_push(uid, reason, keep=100):
    """写一条"立即下线"事件给 yehochat。

    只有 DELETE FROM sessions 是不够的 —— 那只作废了 token，已经握在手里的那条
    websocket 照样活着，被踢的人还能发消息、改名、撤回，直到他自己断线重连。
    两个进程之间只有文件能当信使（和 roster.json 一个路子）：yehochat 每 2 秒扫一次
    mtime，看到新事件就**立刻**把那条连接关掉。详见 server.py 的 revoke_loop。

    reason: "ban"  -> 客户端显示"账号已被封禁"，不再重连
            其它   -> "登录已失效，请重新登录"
    """
    try:
        with _REVOKE_LOCK:
            ev = []
            if os.path.exists(REVOKE_FILE):
                try:
                    with io.open(REVOKE_FILE, "r", encoding="utf-8") as f:
                        d = json.load(f)
                    ev = list((d.get("events") if isinstance(d, dict) else d) or [])
                except Exception:
                    ev = []          # 文件坏了就当没有，重写一份
            # ts 用浮点秒（微秒精度）：反正是单调递增的去重键，
            # 同一次调用里多条事件也不会撞上同一个值。
            ev.append({"uid": int(uid), "reason": str(reason), "ts": time.time()})
            tmp = REVOKE_FILE + ".tmp"
            with io.open(tmp, "w", encoding="utf-8") as f:
                json.dump({"events": ev[-keep:]}, f, ensure_ascii=False)
            os.replace(tmp, REVOKE_FILE)
        log.info("revoke push: uid=%s reason=%s", uid, reason)
    except Exception as e:
        log.info("revoke_push err: %s", e)


def admin_op(action, uid, value=None):
    c = db()
    row = c.execute("SELECT * FROM users WHERE uid=?", (uid,)).fetchone()
    if not row:
        c.close()
        return 404, {"ok": False, "error": "用户不存在"}
    extra = {}          # 需要额外回给前端的数字（比如删号时解除了多少条消息的归属）
    revoke = None       # 非 None 就在 commit 之后推一条"立即下线"事件给 yehochat
    if action == "ban":
        c.execute("UPDATE users SET banned=1 WHERE uid=?", (uid,))
        c.execute("DELETE FROM sessions WHERE uid=?", (uid,))  # 作废旧 token
        revoke = "ban"
    elif action == "unban":
        c.execute("UPDATE users SET banned=0 WHERE uid=?", (uid,))
    elif action == "del":
        # 删账号时把「消息 -> uid」的引用改成 -1（已注销）。
        # uid 现在只增不复用，所以这个号不会再发给别人，不存在"附身"的问题了；
        # 改 -1 纯粹是为了显示：客户端看到 -1 会显示成「昵称（用户已注销）」。
        # （0 是另一回事：那是"老数据没带 uid，不知道是谁"，会回落到昵称快照。）
        detach_msg = detach_quote = 0
        try:
            detach_msg = c.execute("UPDATE msgs SET uid=-1 WHERE uid=?", (uid,)).rowcount
            detach_quote = c.execute(
                "UPDATE msgs SET q_uid=-1 WHERE q_uid=?", (uid,)).rowcount
        except sqlite3.OperationalError:
            pass        # 还没有 msgs 表（全新库 / 聊天服务没起过），跳过
        c.execute("DELETE FROM users WHERE uid=?", (uid,))
        c.execute("DELETE FROM sessions WHERE uid=?", (uid,))
        roster_mark_gone(uid)
        revoke = "kick"
        extra = {"messages_detached": detach_msg, "quotes_detached": detach_quote}
    elif action == "archive":
        # 归档 = 软删除：列表里不再出现、不能登录、名册里挂成"已注销"，
        # 但 users 行 / msgs / 引用全部原样留着，随时可以恢复。
        c.execute("UPDATE users SET archived=1 WHERE uid=?", (uid,))
        c.execute("DELETE FROM sessions WHERE uid=?", (uid,))   # 已登录的踢下线
        roster_mark_archived(uid, True)
        revoke = "kick"
    elif action == "unarchive":
        c.execute("UPDATE users SET archived=0 WHERE uid=?", (uid,))
        roster_mark_archived(uid, False)
    elif action == "set_email":
        if not EMAIL_RE.match(value or ""):
            c.close(); return 400, {"ok": False, "error": "邮箱格式不对"}
        c.execute("UPDATE users SET email=? WHERE uid=?", (value, uid))
    elif action == "set_name":
        if not NAME_RE.match(value or ""):
            c.close(); return 400, {"ok": False, "error": "名字只能 1-20 位中英文/数字"}
        c.execute("UPDATE users SET name=? WHERE uid=?", (value, uid))
    elif action == "kick":
        c.execute("DELETE FROM sessions WHERE uid=?", (uid,))
        revoke = "kick"
    elif action == "set_pw":
        if not value or len(str(value)) < 6:
            c.close(); return 400, {"ok": False, "error": "密码至少 6 位"}
        salt = rand_token(16)
        c.execute("UPDATE users SET pw_salt=?, pw_hash=? WHERE uid=?", (salt, hash_pw(str(value), salt), uid))
        c.execute("DELETE FROM sessions WHERE uid=?", (uid,))
        revoke = "kick"
    elif action == "set_uuid":
        if not UUID_RE.match(value or ""):
            c.close(); return 400, {"ok": False, "error": "UUID 只能字母、数字、下划线，不超过 20 位"}
        if c.execute("SELECT 1 FROM users WHERE uuid=? AND uid<>?", (value, uid)).fetchone():
            c.close(); return 400, {"ok": False, "error": "这个 UUID 已被占用"}
        c.execute("UPDATE users SET uuid=?, uuid_ts=? WHERE uid=?", (value, now(), uid))
    else:
        c.close()
        return 400, {"ok": False, "error": "未知操作"}
    c.commit()
    c.close()
    # commit 之后再推：反过来的话，万一下面报错回滚了，人已经被踢下线了。
    if revoke:
        revoke_push(uid, revoke)
    out = {"ok": True}
    out.update(extra)
    return 200, out


ONLINE_STATE = os.path.join(_CHAT_DIR, "online.json")


def _live_uids():
    """读取 yehochat 写出的真实在线 uid 集合"""
    try:
        with io.open(ONLINE_STATE, "r", encoding="utf-8") as f:
            d = json.load(f)
        if isinstance(d, dict):
            return set(int(x) for x in (d.get("uids") or []))
        # 兼容旧格式（纯名单数组）
        return set()
    except Exception:
        return set()


def sweep_expired_sessions():
    """把超过 LOGIN_K_TTL 未在线的 session 全部删除（强制退出登录）
    保险：当前真实在线的 uid 一律跳过，绝不清除（防"连续在线30天被踢"）"""
    try:
        live = _live_uids()
        c = db()
        rows = c.execute("SELECT token, uid, last_ts FROM sessions").fetchall()
        dead = [(r["token"], r["uid"]) for r in rows
                if (r["uid"] not in live)
                and (now() - (r["last_ts"] or 0) > LOGIN_K_TTL)]
        for tk, uid in dead:
            c.execute("DELETE FROM sessions WHERE token=?", (tk,))
            log.info("sweep: expire session uid=%s (10天未在线)", uid)
        c.commit()
        c.close()
        return len(dead)
    except Exception as e:
        log.info("sweep err: %s", e)
        return 0


def online_list():
    """Token 列表：标注每个 token 的真实在线状态 + 上次在线时间"""
    live = _live_uids()
    nowts = now()
    c = db()
    rows = c.execute("""SELECT s.uid, s.token, s.token AS token_raw, s.last_ts,
               u.uuid, u.name, u.email, u.banned
        FROM sessions s LEFT JOIN users u ON u.uid=s.uid
        WHERE ?-s.last_ts <= ? ORDER BY s.last_ts DESC""",
        (nowts, LOGIN_K_TTL)).fetchall()
    c.close()
    out = []
    touched = []   # 需要心跳续期的 token
    for r in rows:
        d = dict(r)
        uid = d.get("uid")
        last = d.get("last_ts") or 0
        is_online = bool(uid in live)
        d["token"] = (d.get("token") or "")[:8] + "..."
        d["online"] = is_online                  # 真实在线
        if is_online:
            # 心跳续期：真在线就把 last_ts 刷成现在
            # 既让"上次在线"显示为刚刚，也保证永不被 sweep 清掉
            last = nowts
            touched.append(d.get("token_raw") or r["token"])
        d["last_seen"] = int(last)               # 上次在线时间戳
        d["expire_in"] = max(0, int(LOGIN_K_TTL - (nowts - last)))
        out.append(d)
    # 落库续期（用未截断的真实 token）
    if touched:
        try:
            cc = db()
            for tk in touched:
                cc.execute("UPDATE sessions SET last_ts=? WHERE token=?", (nowts, tk))
            cc.commit()
            cc.close()
        except Exception as e:
            log.info("heartbeat update err: %s", e)
    return 200, {"ok": True, "online": out, "count": len(out),
                 "ttl_days": int(LOGIN_K_TTL / 86400)}


def clear_history():
    """清空服务器端聊天历史（msgs 表）"""
    c = db()
    try:
        n = c.execute("SELECT COUNT(*) FROM msgs").fetchone()[0]
        c.execute("DELETE FROM msgs")
        c.commit()
        c.close()
        log.info("clear_history: removed %d msgs", n)
        return 200, {"ok": True, "removed": n}
    except Exception as e:
        c.close()
        return 500, {"ok": False, "error": "清理失败: %s" % e}


def cleanup():
    """每 5 秒清理一次：10 天未在线的 session 强制失效（退出登录）"""
    while True:
        try:
            n = sweep_expired_sessions()
            if n:
                log.info("cleanup: %d session(s) expired", n)
        except Exception as e:
            log.info("cleanup err: %s", e)
        time.sleep(5)


# ---------------- HTTP ----------------
class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _send(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _read(self):
        n = int(self.headers.get("Content-Length") or 0)
        if n <= 0:
            return {}
        try:
            return json.loads(self.rfile.read(n).decode("utf-8"))
        except Exception:
            return {}

    def _tk(self):
        return (self.headers.get("X-Token")
                or self.headers.get("X-Chat-Token")
                or "")

    def do_GET(self):
        u = urlparse(self.path)
        path = u.path.rstrip("/")
        qs = parse_qs(u.query)
        tk = self._tk()
        try:
            if path in ("/ping", "/"):
                return self._send(200, {"ok": True, "srv": "chat_users", "ts": now()})
            if path == "/me":
                uid = check_token(tk)
                if not uid:
                    return self._send(401, {"ok": False, "error": "登录已失效"})
                touch_session(tk)
                return self._send(*do_profile(uid))
            if path == "/admin/list":
                if not self._admin_ok(tk):
                    return self._send(401, {"ok": False, "error": "无管理权限"})
                return self._send(*admin_list(qs.get("q", [""])[0]))
            if path == "/admin/archived":
                if not self._admin_ok(tk):
                    return self._send(401, {"ok": False, "error": "无管理权限"})
                return self._send(*admin_list(qs.get("q", [""])[0], archived=True))
            if path == "/admin/online":
                if not self._admin_ok(tk):
                    return self._send(401, {"ok": False, "error": "无管理权限"})
                return self._send(*online_list())
            return self._send(404, {"ok": False, "error": "not found"})
        except Exception as e:
            log.info("GET err: %s", e)
            return self._send(500, {"ok": False, "error": "server error"})

    def do_POST(self):
        path = urlparse(self.path).path.rstrip("/")
        tk = self._tk()
        try:
            b = self._read()
            # --- 注册 ---
            if path == "/reg/code":
                return self._send(*do_send_code(b.get("email", "")))
            if path == "/reg/verify":
                return self._send(*do_register(b.get("email", ""), b.get("pw", ""),
                                               b.get("pw2", ""), b.get("code", "")))
            # --- 登录 ---
            if path == "/login/pw":
                return self._send(*do_login_pw(b.get("acc", ""), b.get("pw", "")))
            if path == "/login/code":
                return self._send(*do_login_send_code(b.get("acc", "")))
            if path == "/login/verify":
                return self._send(*do_login_code(b.get("acc", ""), b.get("code", "")))
            # --- 密码重置 / 修改（都要邮箱验证码）---
            if path == "/reset/code":
                return self._send(*do_reset_send_code(b.get("acc", "")))
            if path == "/reset/verify":
                return self._send(*do_reset_password(b.get("acc", ""), b.get("code", ""),
                                                     b.get("pw", ""), b.get("pw2", "")))
            if path == "/me/change_code":
                uid = check_token(tk)
                if not uid:
                    return self._send(401, {"ok": False, "error": "登录已失效"})
                c0 = db(); r0 = c0.execute("SELECT email FROM users WHERE uid=?", (uid,)).fetchone(); c0.close()
                if not r0:
                    return self._send(404, {"ok": False, "error": "用户不存在"})
                return self._send(*do_send_code(r0["email"]))
            if path == "/me/change_pw":
                uid = check_token(tk)
                if not uid:
                    return self._send(401, {"ok": False, "error": "登录已失效"})
                return self._send(*do_change_password(uid, b.get("code", ""),
                                                      b.get("pw", ""), b.get("pw2", "")))
            # --- 用户自助（需登录） ---
            if path in ("/set/name", "/set/uuid"):
                uid = check_token(tk)
                if not uid:
                    return self._send(401, {"ok": False, "error": "登录已失效"})
                touch_session(tk)
                if path == "/set/name":
                    return self._send(*do_rename(uid, b.get("name", "")))
                return self._send(*do_set_uuid(uid, b.get("uuid", "")))
            # --- 管理（沿用 admin token 头部 X-Chat-Token 传管理口令）---
            if path.startswith("/admin/"):
                if not self._admin_ok(tk):
                    return self._send(401, {"ok": False, "error": "无管理权限"})
                if path == "/admin/op":
                    return self._send(*admin_op(b.get("action", ""), int(b.get("uid", 0)), b.get("value")))
                if path == "/admin/clear_history":
                    return self._send(*clear_history())
            return self._send(404, {"ok": False, "error": "not found"})
        except Exception as e:
            log.info("POST err: %s", e)
            return self._send(500, {"ok": False, "error": "server error"})

    def _admin_ok(self, tk):
        """先试工具箱日常 token（yeho_auth），再兜底 chat.db 的管理口令。"""
        if tk and yeho_auth is not None:
            try:
                if yeho_auth.check_token(tk):
                    return True
            except Exception:
                pass
        try:
            c = db()
            row = c.execute("SELECT 1 FROM tokens WHERE token=? AND role='admin' AND banned=0", (tk,)).fetchone()
            c.close()
            return bool(row)
        except Exception:
            return False


def main():
    db_init()
    threading.Thread(target=cleanup, daemon=True).start()
    log.info("YehoChat users v1 on 127.0.0.1:%d", PORT)
    ThreadingHTTPServer(("127.0.0.1", PORT), H).serve_forever()


if __name__ == "__main__":
    main()
