# 部署

最小可用：一台能跑 Python 3.10+ 的机器 + 一个域名（可选）。

## 1. 服务端

    git clone <this repo> && cd server
    python -m venv .venv && . .venv/bin/activate
    pip install -r requirements.txt
    cp .env.example .env    # 填 SMTP / OSS，或留空
    pip install cryptography && python gen_cert.py   # 首次：自签证书
    python server.py &       # 8705
    python chat_users.py &   # 8706
    python update_srv.py &   # 8811

生产环境建议用 systemd（三个 unit，Restart=always）。**注意 server.py 重启会断开所有在线连接**，客户端会自动重连。

## 2. nginx（8443）

    location /chatapi/ { proxy_pass http://127.0.0.1:8706/; }
    location /update/  { proxy_pass http://127.0.0.1:8811/; }

证书可以是自签的，但客户端要相应配置信任。

## 3. 管理口令

首次部署自己生成一个，写进 chat.db 的 	okens 表：

    python - <<'PY'
    import sqlite3, secrets
    db = sqlite3.connect("data/chat.db")
    db.execute("INSERT INTO tokens(token, username, role, banned, created_at) VALUES(?,?,?,0,strftime('%s','now'))",
               (secrets.token_urlsafe(32), "admin", "admin"))
    db.commit()
    PY

不要使用任何示例值当口令。

## 4. 客户端

见 ndroid/README.md。服务器地址走 gradle 属性，不要写进源码。