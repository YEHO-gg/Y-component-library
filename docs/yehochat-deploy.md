# 部署

最小可用：一台能跑 Python 3.10+ 的机器 + 一个域名（可选，但 HTTPS 建议有）。

整体是三个进程 + 一层 nginx：

```
客户端 ──WSS(8705)──────────────────► server.py      聊天长连接（直连，不经 nginx 也行）
客户端 ──HTTPS(8443)/chatapi/───────► nginx ──► chat_users.py (8706)   账号 / 管理
客户端 ──HTTPS(8443)/update/────────► nginx ──► update_srv.py  (8811)  版本 / 下载
```

## 1. 服务端

    git clone <this repo>
    cd apps/yehochat/server

    python -m venv .venv && . .venv/bin/activate       # Windows: .venv\Scripts\activate
    pip install -r requirements.txt

    cp .env.example .env          # 填 SMTP（注册验证码用）；留空则注册接口会返回 500
    python gen_cert.py            # 首次：自签证书，落到 data/cert/{cert,key}.pem

    python server.py &            # 8705  聊天 WSS
    python chat_users.py &        # 8706  账号与管理
    python update_srv.py &        # 8811  更新信息

**`.env` 是真会被读的**：`server/yeho_env.py` 在三个入口脚本的最前面加载它，
规则是"已存在的环境变量优先"，所以 systemd 的 `EnvironmentFile` 依然能覆盖 `.env` 里的值。

**数据目录只有一个**：默认 `apps/yehochat/server/data/`，可用 `YEHOO_CHAT_DIR` 改。
`chat.db`（消息 + 账号 + 管理口令）、`users.log`、`server.log`、`uid_seq`、`cert/`、`roster.json` 都在里面。
三个进程必须指向同一个目录，否则会出现"注册成功但登录不上"。

> 从旧版本升级：早期版本的 `chat.db` / `users.log` / `uid_seq` 在代码目录，
> 现在启动时自动搬进 `YEHOO_CHAT_DIR`（只在目标不存在时搬），日志里会打一行提示。

## 2. 端口与防火墙

| 端口 | 要不要对外 | 说明 |
|---|---|---|
| 8705 | **要** | 客户端 WSS **直连**这个端口（`ChatService.java` 里写死 `wss://host:8705`），不经过 nginx |
| 8706 | 不用 | 只给 nginx 反代 `/chatapi/` |
| 8811 | 不用 | 只给 nginx 反代 `/update/` |
| 8443 | 要 | nginx 对外 HTTPS |

只反代了 8706 / 8811 却忘了放开 8705，是"网页能开、App 连不上"的典型原因。

## 3. systemd

仓库里带了三份 unit 模板（`apps/yehochat/server/deploy/`）：
`yehochat.service`、`yehousers.service`、`yehoupdate.service`。

    sudo cp apps/yehochat/server/deploy/*.service /etc/systemd/system/
    sudo systemctl daemon-reload
    sudo systemctl enable --now yehochat yehousers yehoupdate

三份都是 `Restart=always`。**server.py 重启会断开所有在线连接**，客户端会自动重连。

## 4. nginx（8443）

    location /chatapi/ { proxy_pass http://127.0.0.1:8706/; }
    location /update/  { proxy_pass http://127.0.0.1:8811/; }

注意两点：

- 反代 `/update/` 时要带上 `X-Yeho-Proxy` 之类的标记头（`update_srv.py` 靠它判定"这是外部请求"，
  从而强制 `/publish` 校验 token）。新增 location 时别漏。
- 大文件下载走 OSS 签名 URL，nginx 不用配超大 `client_max_body_size`，但 `/publish` 上传要放行够大的 body。

证书可以是自签的。**当前客户端 `Tls.java` 是无条件信任所有证书、也不校验主机名**，
所以"换成正式证书"并不会让它变得更安全；真要收紧，需要在客户端做证书指纹 pinning（见该文件注释）。

## 5. 管理口令

`tokens` 表在 `db_init()` 里会自动创建（全新安装也有），不用再手工建表。
首次部署生成一个口令：

    cd apps/yehochat/server
    python - <<'PY'
    import sqlite3, secrets, os
    d = os.environ.get("YEHOO_CHAT_DIR") or os.path.join(os.path.dirname(os.path.abspath("__file__")), "data")
    db = sqlite3.connect(os.path.join(d, "chat.db"))
    tok = secrets.token_urlsafe(32)
    db.execute("INSERT INTO tokens(token, username, role, banned, created_at) VALUES(?,?,?,0,strftime('%s','now'))",
               (tok, "admin", "admin"))
    db.commit()
    print("admin token:", tok)
    PY

**口令必须进 `data/chat.db`**（和鉴权读的是同一个库）。早期文档让人写 `data/chat.db` 但账号服务读
`server/chat.db`，插错库的话管理接口会一律 401 —— 这个坑现在已经从代码层面修掉了：三处路径统一在 `YEHOO_CHAT_DIR`。

不要使用任何示例值当口令。

## 6. 客户端

见 [apps/yehochat/README.md](../apps/yehochat/README.md)。

服务器地址走 gradle 属性，不写进源码：

    cp apps/yehochat/android/gradle.properties.example apps/yehochat/android/gradle.properties
    # 然后把 chatHost 改成你的域名或 IP

`android/gradle.properties` 已被 `.gitignore` 排除（仓库里只留 `.example` 模板），
注意 project 级属性优先于 `~/.gradle/gradle.properties`，改 home 目录那份是不生效的。
