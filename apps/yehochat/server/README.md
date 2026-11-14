# 服务端

三个独立进程 + 两个内部模块，全部标准库（OSS 模块只在启用对象存储时需要 `oss2`）。

| 文件 | 端口 | 作用 |
|---|---|---|
| `server.py` | 8705 | 聊天 WebSocket（TLS），持有 `chat.db` |
| `chat_users.py` | 8706 | 账号 / 管理 HTTP API，同一个 `chat.db` |
| `update_srv.py` | 8811 | 给客户端下发版本信息 |
| `yeho_auth.py` | — | 口令哈希、令牌生成、邮箱验证码 |
| `yeho_oss.py` | — | 可选：把安装包放对象存储，签名下载 URL |

## 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `YEHOO_CHAT_DIR` | `server/data` | 数据库、名册、证书、日志都在这里 |
| `YEHOO_UPDATE_DIR` | `server/update` | 更新服务读安装包的目录 |
| `YEHOO_SMTP_HOST` | `smtp.example.com` | 验证码邮件用；留空则跳过发信 |
| `YEHOO_SMTP_PORT` | `465` | |
| `YEHOO_SMTP_USER` / `YEHOO_SMTP_PASS` | 空 | 留空则注册时不发验证码 |
| `YEHOO_SMTP_FROM` | 同 USER | |
| `OSS_ACCESS_KEY_ID` / `OSS_ACCESS_KEY_SECRET` | 空 | 仅更新服务用；不填则走本地目录兜底 |

## 首次运行

```bash
python gen_cert.py   # 一次性：生成自签证书到 data/cert/（需要 pip install cryptography）
python server.py     # 自动建表并开始监听
python chat_users.py
```

`chat.db` 的表结构见 `schema.sql`。证书是自签的，客户端 `Tls.java` 里做了对应信任；换成正式证书后要把那段去掉。

## 注意

- `server.py` 会写 `online.json`，`chat_users.py` 也会读它，用来在管理端显示在线人数。
- 管理接口靠 `tokens` 表里的管理口令鉴权，首次部署请自己 `INSERT` 一条，不要用任何示例值。
- 两个进程共用 `chat.db`，SQLite 已开 WAL；不要把 `YEHOO_CHAT_DIR` 放到网络盘上。
