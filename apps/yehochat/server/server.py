#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
YEHO Chat Server v4
- 登录：携带登录K (type=login, token) -> 校验 chat.db.sessions
- 消息：仅文字，带 发送人(name/uuid)
- 缓存：只保留最近 1 天且最多 150 条，每 10 秒循环清理
"""
import asyncio, json, time, logging, ssl, sqlite3, os, re, shutil, sys
import websockets

# 先读 .env，再读 os.environ；否则环境变量里的 SMTP/OSS 配置会被忽略
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import yeho_env
yeho_env.load_env()

# ---- 可移植配置（开源版新增）------------------------------------------
# 所有路径与凭据都从环境变量读，源码里不再出现真实服务器路径。
_CHAT_DIR = os.environ.get("YEHOO_CHAT_DIR") or os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "data")
_UPDATE_DIR = os.environ.get("YEHOO_UPDATE_DIR") or os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "update")
# ----------------------------------------------------------------------

os.makedirs(_CHAT_DIR, exist_ok=True)

# 老版本把 chat.db 放在代码目录，升级后自动搬进 YEHOO_CHAT_DIR，避免"消息全没了"
_legacy_db = os.path.join(os.path.dirname(os.path.abspath(__file__)), "chat.db")
if os.path.exists(_legacy_db) and not os.path.exists(os.path.join(_CHAT_DIR, "chat.db")):
    try:
        shutil.move(_legacy_db, os.path.join(_CHAT_DIR, "chat.db"))
    except OSError:
        pass


PORT = 8705
CERT = os.path.join(_CHAT_DIR, "cert", "cert.pem")
KEY = os.path.join(_CHAT_DIR, "cert", "key.pem")
LOG_FILE = os.path.join(_CHAT_DIR, "server.log")
DB = os.path.join(_CHAT_DIR, "chat.db")
STATE = os.path.join(_CHAT_DIR, "online.json")

# 缓存规则
KEEP_SECONDS = 30 * 24 * 3600   # 留 30 天
KEEP_BYTES = 50 * 1024 * 1024   # 且总量不超过 50 MB（按正文字节算）
KEEP_MAX = 20000                # 条数安全阀：正常轮不到它，体积闸会先触发
CLEAN_INTERVAL = 10             # 每 10 秒清理一次
SYNC_FULL_MAX = 3000            # 强制同步一次最多回推多少条（别一次怼太多）

MAX_TEXT = 100                # 单条消息字符上限（clean_text 统一截断：正文 / 引用快照 / 撤回指令都用它）
HISTORY_LIMIT = 150
ROSTER_FILE = os.path.join(_CHAT_DIR, "roster.json")   # uuid -> 昵称，改名后所有客户端都能查到最新名字
RECALL_SECONDS = 120           # 撤回时限：2 分钟
MAX_MSG_PER_10S = 20
MAX_CONN_PER_IP = 1000
LOGIN_K_TTL = 30 * 86400
REVOKE_FILE = os.path.join(_CHAT_DIR, "revoke.json")   # 管理工具"立即下线"事件（见 revoke_loop）
REVOKE_POLL = 2                                    # 多久扫一次 revoke.json（秒）

logging.basicConfig(level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    handlers=[logging.FileHandler(LOG_FILE), logging.StreamHandler()])
log = logging.getLogger("yehochat")

ONLINE = {}       # uid -> {id(ws): ws}   ★多设备：一个 uid 可以有多条连接
NAMES = {}        # uid -> name
UUID = {}         # uid -> uuid
IP_CONN = {}
_IP_LIVE = {}   # ip -> {连接id: 最后活跃时间}
RATE = {}


def db():
    c = sqlite3.connect(DB, timeout=10)
    c.row_factory = sqlite3.Row
    return c


# 消息读取统一列顺序，供 _item() 按下标取值
MSG_COLS = "ts, name, uuid, text, q_from, q_text, q_ts, sys, uid, q_uid"


def _item(r):
    """把一行 msgs 记录转成下发用的消息项（含引用快照）。

    name / q_from 都是"当时那一刻"的昵称快照，只当兜底用；
    客户端优先拿 uuid / q_uuid 去名册里查当前昵称，这样改名后历史消息也会跟着变。
    """
    return {
        "from": r[1], "uuid": r[2] or "", "text": r[3], "ts": r[0],
        "q_from": (r[4] or "") if len(r) > 4 else "",
        "q_text": (r[5] or "") if len(r) > 5 else "",
        "q_ts": (r[6] or 0) if len(r) > 6 else 0,
        "sys": int(r[7] or 0) if len(r) > 7 else 0,
        # uid 才是"同一个人"的稳定标识（uuid 用户可以自己改），名字靠它去名册里查
        "uid": int(r[8] or 0) if len(r) > 8 else 0,
        "q_uid": int(r[9] or 0) if len(r) > 9 else 0,
    }


def db_init():
    c = db()
    c.execute("CREATE TABLE IF NOT EXISTS msgs (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, name TEXT, uuid TEXT, text TEXT, q_from TEXT, q_text TEXT, q_ts INTEGER, sys INTEGER DEFAULT 0, uid INTEGER, q_uid INTEGER)")
    # 老库迁移：这些列都是后加的，已有的表要补上（ADD COLUMN 不动老数据）
    try:
        have = {row["name"] for row in c.execute("PRAGMA table_info(msgs)").fetchall()}
        for col, decl in (("q_from", "TEXT"), ("q_text", "TEXT"), ("q_ts", "INTEGER"),
                          ("sys", "INTEGER DEFAULT 0"),
                          ("uid", "INTEGER"), ("q_uid", "INTEGER")):
            if col not in have:
                c.execute("ALTER TABLE msgs ADD COLUMN %s %s" % (col, decl))
                log.info("migrate: msgs.%s added", col)
    except Exception as e:
        log.info("db_init migrate err: %s", e)
    c.commit()
    c.close()


def db_add(name, uuid, text, q_from="", q_text="", q_ts=0, ts=None,
           uid=0, q_uid=0):
    """ts 可由调用方传入：必须与 broadcast 用同一个值，否则客户端去重键对不上。"""
    try:
        c = db()
        c.execute("INSERT INTO msgs (ts, name, uuid, text, q_from, q_text, q_ts, sys, uid, q_uid)"
                  " VALUES (?,?,?,?,?,?,?,0,?,?)",
                  (int(time.time()) if ts is None else int(ts),
                   name, uuid, text, q_from or "", q_text or "", int(q_ts or 0),
                   int(uid or 0), int(q_uid or 0)))
        c.commit()
        c.close()
    except Exception as e:
        log.info("db_add err: %s", e)


def db_add_sys(name, uuid, ts, uid=0):
    """撤回提示（sys=1 的一行）。

    text 故意留空：文案由客户端拿 uuid 去名册里现算（"XXX 撤回了一条消息"），
    这样才能满足"改了名以后，连以前那条撤回提示里的名字也跟着变"。
    """
    try:
        c = db()
        c.execute("INSERT INTO msgs (ts, name, uuid, text, q_from, q_text, q_ts, sys, uid, q_uid)"
                  " VALUES (?,?,?,'','','',0,1,?,0)",
                  (int(ts), name or "", uuid or "", int(uid or 0)))
        c.commit()
        c.close()
        return True
    except Exception as e:
        log.info("db_add_sys err: %s", e)
        return False


# 一条消息占多少字节：正文 + 引用快照。LENGTH() 对 TEXT 返回的是**字符数**，
# 要按字节算必须 CAST 成 BLOB，否则中文会被少算成 1/3。
_SQL_BYTES = ("SELECT COALESCE(SUM(LENGTH(CAST(text AS BLOB))"
              " + LENGTH(CAST(COALESCE(q_text,'') AS BLOB))), 0) FROM msgs")


def db_trim():
    """三重闸：超 30 天 / 超 50MB / 超 KEEP_MAX 条，从最老的开始删。"""
    try:
        c = db()
        # ① 时间
        cutoff = int(time.time()) - KEEP_SECONDS
        c.execute("DELETE FROM msgs WHERE ts < ?", (cutoff,))

        # ② 体积：按平均每条大小估算要删多少条，一次删够；
        #    估算不精确没关系，每 10 秒还会再跑一遍，会收敛。
        total = c.execute(_SQL_BYTES).fetchone()[0] or 0
        n = c.execute("SELECT COUNT(*) FROM msgs").fetchone()[0] or 0
        if total > KEEP_BYTES and n > 0:
            avg = max(1, total // n)
            drop = (total - KEEP_BYTES) // avg + 50
            c.execute("DELETE FROM msgs WHERE id IN "
                      "(SELECT id FROM msgs ORDER BY id ASC LIMIT ?)", (drop,))
            log.info("db_trim 体积超限: %.1fMB > %.1fMB，删了约 %d 条",
                     total / 1048576.0, KEEP_BYTES / 1048576.0, drop)

        # ③ 条数安全阀
        n = c.execute("SELECT COUNT(*) FROM msgs").fetchone()[0]
        if n > KEEP_MAX:
            c.execute("DELETE FROM msgs WHERE id IN (SELECT id FROM msgs ORDER BY id DESC LIMIT -1 OFFSET ?)",
                      (KEEP_MAX,))
        c.commit()
        c.close()
    except Exception as e:
        log.info("db_trim err: %s", e)


async def clean_loop():
    while True:
        await asyncio.sleep(CLEAN_INTERVAL)
        await asyncio.get_event_loop().run_in_executor(None, db_trim)


def db_recent(n):
    try:
        c = db()
        rows = c.execute("SELECT %s FROM msgs ORDER BY id DESC LIMIT ?" % MSG_COLS, (n,)).fetchall()
        c.close()
        return list(reversed(rows))
    except Exception as e:
        log.info("db_recent err: %s", e)
        return []


def db_since(since_ts, full, limit):
    """增量同步：只取 ts >= since_ts 的消息；full=True 时取全部缓存。

    用 >= 而非 > ：ts 是秒级精度，同秒内的消息用 > 会漏掉，
    宁可多带几条让客户端按自身去重键丢掉，也不能丢消息。
    """
    try:
        c = db()
        if full:
            rows = c.execute("SELECT %s FROM msgs ORDER BY id ASC LIMIT ?" % MSG_COLS,
                             (limit,)).fetchall()
        elif since_ts and since_ts > 0:
            rows = c.execute("SELECT %s FROM msgs WHERE ts >= ? ORDER BY id ASC LIMIT ?" % MSG_COLS,
                             (int(since_ts), limit)).fetchall()
        else:
            rows = c.execute("SELECT %s FROM msgs ORDER BY id DESC LIMIT ?" % MSG_COLS,
                             (limit,)).fetchall()
            rows = list(reversed(rows))
        c.close()
        return rows
    except Exception as e:
        log.info("db_since err: %s", e)
        return []


def db_recall(uuid, name, ts, text):
    """撤回：删除本人发的、指定 (ts, text) 的那条。返回受影响行数。"""
    try:
        c = db()
        cur = c.execute(
            "DELETE FROM msgs WHERE ts=? AND text=? AND (uuid=? OR (COALESCE(uuid,'')='' AND name=?))",
            (int(ts), text, uuid or "", name or ""))
        c.commit()
        n = cur.rowcount
        c.close()
        return n
    except Exception as e:
        log.info("db_recall err: %s", e)
        return 0


ROSTER = {}          # uid(字符串形式，JSON 的 key 只能是字符串) -> 昵称
                     # 用 uid 而不是 uuid：uuid 用户每 30 天能自己改一次，uid 不可变
ROSTER_GONE = set()  # 已注销的 uid（int）—— 客户端据此把老消息标成"已注销"
ROSTER_ARCHIVED = set()  # 归档的 uid（int）—— 跟 ROSTER_GONE 在客户端是同一个显示效果，
                         # 分开存是因为归档可以撤销、注销不能
_ROSTER_MTIME = 0.0  # roster.json 上次被读进来时的修改时间，用来发现外部改写


def _roster_split(d):
    """兼容三种名册格式：
        v1（旧）: {"1": "YEHO"}                                    —— 只有昵称
        v2:       {"users": {...}, "deleted": [3]}
        v3:       {"users": {...}, "deleted": [3], "archived": [4]}
    """
    if isinstance(d, dict) and ("users" in d or "deleted" in d or "archived" in d):
        return (dict(d.get("users") or {}),
                set(int(x) for x in (d.get("deleted") or [])),
                set(int(x) for x in (d.get("archived") or [])))
    return dict(d or {}), set(), set()


def roster_load():
    global ROSTER, ROSTER_GONE, ROSTER_ARCHIVED, _ROSTER_MTIME
    try:
        ROSTER, ROSTER_GONE, ROSTER_ARCHIVED = {}, set(), set()
        if os.path.exists(ROSTER_FILE):
            _ROSTER_MTIME = os.path.getmtime(ROSTER_FILE)
            with open(ROSTER_FILE, encoding="utf-8") as f:
                ROSTER, ROSTER_GONE, ROSTER_ARCHIVED = _roster_split(json.load(f))
        log.info("roster loaded: %d users, %d gone, %d archived",
                 len(ROSTER), len(ROSTER_GONE), len(ROSTER_ARCHIVED))
    except Exception as e:
        log.info("roster load err: %s", e)
        ROSTER, ROSTER_GONE, ROSTER_ARCHIVED = {}, set(), set()


def roster_save():
    global _ROSTER_MTIME
    try:
        tmp = ROSTER_FILE + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump({"users": ROSTER, "deleted": sorted(ROSTER_GONE),
                       "archived": sorted(ROSTER_ARCHIVED)},
                      f, ensure_ascii=False)
        os.replace(tmp, ROSTER_FILE)     # 原子替换，避免写一半被读到
        # 自己刚写的，记下 mtime，免得下面的 watcher 把它当成"外部改写"又广播一遍
        _ROSTER_MTIME = os.path.getmtime(ROSTER_FILE)
    except Exception as e:
        log.info("roster save err: %s", e)


def roster_set(uid, name):
    """登记 uid -> 昵称。返回是否真的变了（没变就不用广播）"""
    if not uid or not name:
        return False
    k = str(uid)
    if ROSTER.get(k) == name:
        return False
    ROSTER[k] = name
    # 这里原先有一句 ROSTER_GONE.discard(uid)：那时 uid 会被回收，注销标记得让位。
    # 现在 uid 只增不复用，同一个号不可能再属于别人，注销标记就该永久留着 ——
    # 留着它，任何客户端不管隔多久连上来，都能知道"这个号的人已经注销了"，
    # 从而把自己缓存里的老消息也改过来。
    roster_save()
    return True


async def broadcast_roster():
    await broadcast({"type": "roster", "users": ROSTER,
                     "deleted": sorted(ROSTER_GONE),
                     "archived": sorted(ROSTER_ARCHIVED)})


async def roster_watch_loop():
    """盯着 roster.json 的修改时间，一变就重读并广播。

    名册会被 chat_users.py（管理工具删号 / 改名）从外部改写，而那个进程没法直接
    给聊天客户端发消息 —— 只能拿文件当信使。没有这一段，删掉的人要等到别人下次
    登录才会变成"已注销"。
    """
    while True:
        await asyncio.sleep(CLEAN_INTERVAL)
        try:
            m = os.path.getmtime(ROSTER_FILE) if os.path.exists(ROSTER_FILE) else 0.0
            if m and m != _ROSTER_MTIME:
                roster_load()
                await broadcast_roster()
                log.info("roster changed on disk -> rebroadcast")
        except Exception as e:
            log.info("roster watch err: %s", e)


# 这里原本有个 is_banned()，只查 users.banned 一列。它就是"踢了没反应"的元凶：
# 踢出走的是 DELETE FROM sessions，banned 没动，它看不见。已被 session_state() 取代，
# 别再把它加回来 —— 判断登录态请一律用 session_state()。


def check_login(token):
    """校验登录K，返回 (uid, name, uuid) 或 None"""
    if not token or not isinstance(token, str):
        return None
    try:
        c = db()
        s = c.execute("SELECT uid,last_ts FROM sessions WHERE token=?", (token,)).fetchone()
        if not s:
            c.close()
            return None
        if time.time() - s["last_ts"] > LOGIN_K_TTL:
            c.close()
            return None
        u = c.execute("SELECT uid,name,uuid,banned FROM users WHERE uid=?", (s["uid"],)).fetchone()
        if not u:
            c.close()
            return None
        if u["banned"]:
            c.close()
            return ("BANNED", "", "")
        # 每次连接刷新 last_ts（保持"一直连不失效"）
        c.execute("UPDATE sessions SET last_ts=? WHERE token=?", (int(time.time()), token))
        c.commit()
        c.close()
        return (u["uid"], u["name"] or "", u["uuid"] or "")
    except Exception as e:
        log.info("check_login err: %s", e)
        return None


def session_state(token, uid):
    """每条消息都重验一次登录态。返回 (state, name, uuid)。

    state:
      "ok"      正常
      "gone"    token 被作废了 —— 踢出 / 改密 / 归档 / 删号 / 会话过期
      "banned"  账号被封禁

    为什么不能只用 is_banned()
        is_banned() 只读 users.banned 一列。而「踢出」「改密码」「归档」「删号」
        做的统统是 DELETE FROM sessions —— 删的是 token，banned 还是 0，
        所以那个检查对它们**完全瞎**。连接就这么一直活着：被踢的人照样能发消息、
        改名、撤回，直到他自己断线重连才发现"登录已失效"。这就是"踢了没反应"。

    和 check_login() 的区别
        check_login() 在登录那一刻调用，会 UPDATE last_ts 续期。
        这个函数在热路径上，**每条消息**都要跑，所以只读、不写库；
        也不看 TTL —— 连接还在就说明人还在线，登录时已经续过期了。
        （TTL 的兜底交给 chat_users.py 的 sweep_expired_sessions，它会跳过真实在线的 uid。）

    库里抖一下（比如被管理工具锁住）绝不能把人踢了，所以出错一律当 "ok"。
    """
    try:
        c = db()
        s = c.execute("SELECT uid FROM sessions WHERE token=?", (token,)).fetchone()
        if not s or int(s["uid"] or 0) != int(uid):
            c.close()
            return ("gone", "", "")
        u = c.execute("SELECT name,uuid,banned,archived FROM users WHERE uid=?",
                      (uid,)).fetchone()
        c.close()
        if not u:
            return ("gone", "", "")
        if u["banned"]:
            return ("banned", "", "")
        try:
            if int(u["archived"] or 0) == 1:      # 老库还没迁出 archived 列
                return ("gone", "", "")
        except Exception:
            pass
        return ("ok", u["name"] or "", u["uuid"] or "")
    except Exception as e:
        log.info("session_state err: %s", e)
        return ("ok", "", "")


async def _drop(ws, payload):
    """发一条终止提示，然后关连接。清理交给 handle() 的 finally，这里不重复摘表。"""
    try:
        await send_json(ws, payload)
    except Exception:
        pass
    try:
        await ws.close()
    except Exception:
        pass


_REVOKE_MAX_TS = 0.0      # 已处理过的最大事件时间戳（事件按 ts 递增，用它去重）
_REVOKE_MTIME = 0.0


async def revoke_loop():
    """盯着 revoke.json：管理工具踢人 / 封禁 / 改密后要**立刻**把人踢下线。

    光有 session_state 还不够 —— 那只在"用户下一条消息"时才生效。
    被踢的人要是挂后台不动，可能几小时都发现不了，还是"踢了没反应"。
    这里补的正是"立刻"这一半。

    两个进程之间只有文件能当信使（chat_users.py 是独立进程，连不上这里的
    websocket），和 roster.json 一个路子。
    """
    global _REVOKE_MAX_TS, _REVOKE_MTIME
    while True:
        await asyncio.sleep(REVOKE_POLL)
        try:
            if not os.path.exists(REVOKE_FILE):
                continue
            m = os.path.getmtime(REVOKE_FILE)
            if m == _REVOKE_MTIME:
                continue
            _REVOKE_MTIME = m
            try:
                with open(REVOKE_FILE, encoding="utf-8") as f:
                    d = json.load(f)
            except Exception:
                continue
            ev = d.get("events") if isinstance(d, dict) else d
            for e in (ev or []):
                if not isinstance(e, dict):
                    continue
                try:
                    ts = float(e.get("ts") or 0)
                except Exception:
                    continue
                if ts <= _REVOKE_MAX_TS:
                    continue
                _REVOKE_MAX_TS = ts
                try:
                    u = int(e.get("uid") or 0)
                except Exception:
                    continue
                conns = ONLINE.get(u)
                if not conns:
                    continue                      # 人不在线，下次登录自然登不进来
                reason = str(e.get("reason") or "kick")
                for w in list(conns.values()):
                    if reason == "ban":
                        await _drop(w, {"type": "banned", "msg": "账号已被封禁"})
                    else:
                        await _drop(w, {"type": "error", "msg": "登录已失效，请重新登录"})
                ONLINE.pop(u, None)
                log.info("revoke: uid=%s reason=%s -> closed", u, reason)
        except Exception as e:
            log.info("revoke loop err: %s", e)


def clean_text(s):
    if not isinstance(s, str):
        return ""
    s = s.replace("\x00", "")
    s = "".join(ch for ch in s if ch == "\n" or ch == "\t" or ord(ch) >= 32)
    return s[:MAX_TEXT]


def rate_ok(uid):
    now = time.time()
    q = RATE.setdefault(uid, [])
    q[:] = [t for t in q if now - t < 10]
    if len(q) >= MAX_MSG_PER_10S:
        return False
    q.append(now)
    return True


def dump_online():
    """写出真实在线名单：{"names":[...], "uids":[1,2], "ts":时间戳}"""
    try:
        data = {
            "names": sorted(NAMES.values()),
            "uids": sorted(int(u) for u in ONLINE.keys()),
            "ts": int(time.time()),
        }
        tmp = STATE + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False)
        os.replace(tmp, STATE)
    except Exception as e:
        log.info("dump_online err: %s", e)


async def send_json(ws, obj):
    if isinstance(obj, dict):
        if "ts" not in obj:
            # 只有在调用方**没给** ts 时才补一个服务器时间戳（当序号/心跳用）。
            #
            # ⚠️ 这里以前是**无条件覆盖** o["ts"] = _ms，把 msg 广播里的
            #    "ts": ts_now 和 recall 广播里的 "ts": rts 一起冲掉了 ——
            #    客户端拿到的 ts 变成"到达时刻"而不是"发送时刻"。
            #    网络快的时候两者差不到 1 秒，平时看不出来；但撤回要靠
            #    (ts, from, text) 精确匹配原消息，差一秒就永远找不到，
            #    表现是"撤回提示出来了、原消息不消失"。
            obj = dict(obj)
            obj["ts"] = int(time.time() * 1000)
    await ws.send(json.dumps(obj, ensure_ascii=False))


async def broadcast(payload):
    for uid, conns in list(ONLINE.items()):
        for w in list(conns.values()):
            try:
                await send_json(w, payload)
            except Exception:
                pass


async def handle(ws):
    uid = None
    ip = "?"
    try:
        ip = ws.remote_address[0] if ws.remote_address else "?"
        _now = time.time()
        _live = _IP_LIVE.setdefault(ip, {})
        # 清理 5 分钟没更新的僵尸计数
        for _k in [k for k, v in _live.items() if _now - v > 300]:
            _live.pop(_k, None)
        _live[id(ws)] = _now
        if len(_live) > MAX_CONN_PER_IP:
            await send_json(ws, {"type": "error", "msg": "too many connections"})
            await ws.close()
            return

        async for raw in ws:
            if len(raw) > 4096:
                continue
            try:
                msg = json.loads(raw)
            except Exception:
                continue
            if not isinstance(msg, dict):
                continue

            mtype = msg.get("type")
            if uid is None:
                if mtype != "login":
                    await send_json(ws, {"type": "error", "msg": "login first"})
                    continue
                token = msg.get("token", "")
                info = check_login(token)
                if not info:
                    await send_json(ws, {"type": "error", "msg": "登录已失效，请重新登录"})
                    await ws.close()
                    continue
                if info[0] == "BANNED":
                    await send_json(ws, {"type": "banned", "msg": "账号已被封禁"})
                    await ws.close()
                    continue
                uid, name, uuid = info
                # 多设备并存：同一 uid 允许多条连接（手机 + 电脑同时在线）
                ONLINE.setdefault(uid, {})[id(ws)] = ws
                NAMES[uid] = name or uuid
                UUID[uid] = uuid
                dump_online()
                # 名册：登录的人登记进去；同时把整份名册发给他，历史消息才解析得出当前昵称。
                # 先重读一遍磁盘 —— 管理工具删号时会从 roster.json 里摘掉那个 uid，
                # 只在启动时读一次的话，进程里的旧副本会把摘掉的名字又写回去。
                roster_load()
                roster_set(uid, NAMES[uid])
                await send_json(ws, {"type": "login_ok", "name": NAMES[uid], "uuid": uuid})
                await send_json(ws, {"type": "roster", "users": ROSTER,
                                     "deleted": sorted(ROSTER_GONE),
                                     "archived": sorted(ROSTER_ARCHIVED)})
                hist = db_recent(HISTORY_LIMIT)
                if hist:
                    await send_json(ws, {"type": "history", "items": [_item(h) for h in hist],
                                         "full": False, "mode": "login"})
                log.info("login: %s(%s) from %s", NAMES[uid], uuid, ip)
                continue

            # 每条消息都重验一次登录态（msg / recall / sync / ping / name 一律过这道）。
            # 以前只在连接那一刻验一次，之后就只看 is_banned()，而"踢出"走的是
            # DELETE FROM sessions —— 删的是 token，banned 还是 0，所以踢了等于没踢。
            st, nm_now, uu_now = session_state(token, uid)
            if st != "ok":
                if st == "banned":
                    await send_json(ws, {"type": "banned", "msg": "账号已被封禁"})
                else:
                    await send_json(ws, {"type": "error", "msg": "登录已失效，请重新登录"})
                _c = ONLINE.get(uid)
                if _c:
                    _c.pop(id(ws), None)
                    if not _c:
                        ONLINE.pop(uid, None)
                await ws.close()
                break
            # 顺手把内存里的昵称/uuid 跟库对齐。管理员改完名或改完 uuid 之后，
            # 这条连接原本一直抱着登录那一刻的旧值 —— 广播出去的 uuid 是过期的，
            # 要等用户自己重连才更新。
            if nm_now and nm_now != NAMES.get(uid):
                NAMES[uid] = nm_now
                # 落名册之前先确认内存里这份不是过期的：管理工具可能刚改过
                # roster.json（归档 / 删号），拿旧副本回写会把它那笔改动冲掉。
                try:
                    if os.path.getmtime(ROSTER_FILE) != _ROSTER_MTIME:
                        roster_load()
                except Exception:
                    pass
                if roster_set(uid, nm_now):
                    await broadcast_roster()
            if uu_now and uu_now != UUID.get(uid):
                UUID[uid] = uu_now
            if mtype == "msg":
                text = clean_text(msg.get("text", ""))
                if not text.strip():
                    continue
                if not rate_ok(uid):
                    await send_json(ws, {"type": "error", "msg": "too fast"})
                    continue
                nm = NAMES.get(uid, "?")
                # 引用快照：只接受文本，不做任何解析/回链，纯展示用
                q_from = clean_text(msg.get("q_from", ""))[:20]
                q_text = clean_text(msg.get("q_text", ""))[:120]
                try:
                    q_uid = int(msg.get("q_uid") or 0)
                except Exception:
                    q_uid = 0
                try:
                    q_ts = int(msg.get("q_ts") or 0)
                except Exception:
                    q_ts = 0
                # ⚠️ ts 必须只取一次：入库和推送若各自取值，跨秒边界会差 1 秒，
                #    客户端按 (ts, from, text) 去重就会失败，重连补推时该条重复出现。
                ts_now = int(time.time())
                db_add(nm, UUID.get(uid, ""), text, q_from, q_text, q_ts,
                       ts=ts_now, uid=uid, q_uid=q_uid)
                await broadcast({"type": "msg", "from": nm, "uuid": UUID.get(uid, ""),
                                 "uid": uid,
                                 "text": text, "ts": ts_now,
                                 "q_from": q_from, "q_text": q_text, "q_ts": q_ts,
                                 "q_uid": q_uid, "sys": 0})
            elif mtype == "recall":
                # 撤回：本人 + 2 分钟内
                try:
                    rts = int(msg.get("ts") or 0)
                except Exception:
                    rts = 0
                rtext = clean_text(msg.get("text", ""))
                nm = NAMES.get(uid, "?")
                uu = UUID.get(uid, "")
                now = int(time.time())
                if not rts or not rtext:
                    await send_json(ws, {"type": "error", "msg": "撤回参数不完整"})
                    continue
                if now - rts > RECALL_SECONDS:
                    await send_json(ws, {"type": "error", "msg": "超过 2 分钟，不能撤回了"})
                    continue
                n = db_recall(uu, nm, rts, rtext)
                if n <= 0:
                    await send_json(ws, {"type": "error", "msg": "撤回失败：不是本人发的或已不存在"})
                    continue
                # 提示本身也要落库 —— 离线的人下次同步才看得到"谁撤回了这条"。
                # tip_ts 一并广播：在线客户端本地补的那条提示必须用同一个 ts，
                # 否则两边去重键对不上，重连补推时会冒出两条一模一样的提示。
                tip_ts = int(time.time())
                db_add_sys(nm, uu, tip_ts, uid)
                log.info("recall: uid=%s ts=%s rows=%d tip=%s", uid, rts, n, tip_ts)
                await broadcast({"type": "recall", "from": nm, "uuid": uu, "uid": uid,
                                 "ts": rts, "text": rtext, "tip_ts": tip_ts})
            elif mtype == "sync":
                # 增量/强制同步。full=true 取全部缓存（受 KEEP_MAX 限制）
                full = bool(msg.get("full"))
                try:
                    since = int(msg.get("since") or 0)
                except Exception:
                    since = 0
                rows = db_since(since, full, SYNC_FULL_MAX if full else HISTORY_LIMIT)
                await send_json(ws, {"type": "history", "items": [_item(r) for r in rows],
                                     "full": full, "mode": "sync", "since": since})
                log.info("sync: uid=%s full=%s since=%s -> %d items", uid, full, since, len(rows))
            elif mtype == "ping":
                await send_json(ws, {"type": "pong", "ts": int(time.time())})
            elif mtype == "name":
                # 用户改名。原先只改内存里的 NAMES[uid]，别人那边啥都不知道 ——
                # 现在要落名册并广播，其他人的消息页 / 引用块 / 撤回提示才会跟着换名。
                nm = clean_text(msg.get("name", "")).strip()[:20]
                if nm:
                    NAMES[uid] = nm
                    dump_online()
                    if roster_set(uid, nm):
                        await broadcast_roster()
                        log.info("rename: uid=%s -> %s (roster broadcast)", uid, nm)
            else:
                await send_json(ws, {"type": "error", "msg": "unknown cmd"})

    except Exception as e:
        log.info("conn end: %s", e)
    finally:
        if ip and ip in _IP_LIVE:
            _IP_LIVE[ip].pop(id(ws), None)
            if not _IP_LIVE[ip]:
                _IP_LIVE.pop(ip, None)
        if uid is not None:
            _c = ONLINE.get(uid)
            if _c and id(ws) in _c:
                _c.pop(id(ws), None)
                if not _c:
                    ONLINE.pop(uid, None)
                    NAMES.pop(uid, None)
                    UUID.pop(uid, None)
                    dump_online()
                    log.info("logout: uid=%s", uid)


async def main():
    db_init()
    db_trim()
    roster_load()
    asyncio.create_task(clean_loop())
    asyncio.create_task(roster_watch_loop())
    asyncio.create_task(revoke_loop())
    log.info("YEHO Chat Server v4 starting on 0.0.0.0:%d", PORT)
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(CERT, KEY)
    async with websockets.serve(handle, "0.0.0.0", PORT,
                                ping_interval=45, ping_timeout=90,
                                max_size=8192, ssl=ctx):
        await asyncio.Future()


if __name__ == "__main__":
    asyncio.run(main())