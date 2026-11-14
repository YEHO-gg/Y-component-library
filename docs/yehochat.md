# YehoChat —— 自建小圈子聊天

`apps/yehochat`。一个 Python 服务端 + 一个 Android 客户端，自己部署、自己用。

不是微信替代品，目标很窄：**十几个人、要长期住在一个房间里、不依赖任何第三方平台**。

## 它有什么

- **账号**：注册 / 登录 / 改密码 / 改昵称 / 改 uuid（30 天一次），邮箱验证码找回
- **聊天**：WebSocket 长连接、引用回复、撤回、按时间稳定排序、未读与"跳到最新"
- **身份**：不可变 `uid` + 用户可改的 `uuid`；改名后所有历史消息跟着显示新名字（名册广播）
- **单端登录**：同一账号同一时间只保留一个登录态。新设备登录会把旧 token 作废（`new_session` 会 `DELETE FROM sessions WHERE uid=?`），旧连接随后被服务端判定失效并断开
- **管理**：封禁 / 归档（软删）/ 立即下线 / 清空历史，按管理口令鉴权
- **更新**：独立更新服务，给客户端下发版本信息

## 组成

| 路径 | 内容 |
|---|---|
| `apps/yehochat/server/` | 三个进程 + 两个内部模块，见 [server/README.md](../apps/yehochat/server/README.md) |
| `apps/yehochat/android/` | Java 客户端，见 [android/README.md](../apps/yehochat/android/README.md) |

三个进程：

| 文件 | 端口 | 作用 |
|---|---|---|
| `server.py` | 8705 | 聊天 WebSocket（TLS） |
| `chat_users.py` | 8706 | 账号 / 管理 HTTP |
| `update_srv.py` | 8811 | 更新信息 |

## 本地跑起来

```bash
cd apps/yehochat/server
python -m venv .venv && . .venv/bin/activate     # Windows: .venv\Scripts\activate
pip install -r requirements.txt
cp .env.example .env                             # 按需填 SMTP，服务启动时自动读

python gen_cert.py            # 首次：自签证书（要 pip install cryptography）
python server.py              # :8705
python chat_users.py          # :8706
python update_srv.py          # :8811
```

**数据都落在同一个目录**：`server/data/`（或 `YEHOO_CHAT_DIR` 指定的目录），包括
`chat.db`（消息 + 账号 + 管理口令）、`users.log`、`server.log`、`uid_seq`、`cert/`、`roster.json`。
三个进程读写的是同一个 `chat.db`，不要把它们指向不同的目录，否则会出现"注册了但登录不上"。

> 从旧版本升级：早期版本的 `chat.db` / `users.log` / `uid_seq` 放在代码目录，
> 现在启动时会自动搬进 `YEHOO_CHAT_DIR`（只在目标不存在时搬，不会覆盖），日志里会打一行提示。

表结构见 [`server/schema.sql`](../apps/yehochat/server/schema.sql)。
`.env` 由 [`server/yeho_env.py`](../apps/yehochat/server/yeho_env.py) 解析（零依赖），
真实环境变量优先级高于 `.env`。

Android：

```bash
cd apps/yehochat/android
# 服务器地址不写进源码，写在 apps/yehochat/android/gradle.properties（仓库里只有 .example 模板）：
#   chatHost=你的域名或IP
cp gradle.properties.example gradle.properties   # 首次
./gradlew assembleDebug
```

## 设计上几个刻意的选择

- **uid 只增不复用**。注销掉最大号后如果按 `max(uid)+1` 发号，下一个注册者会拿到同一个号，而老消息还挂在那个号上 —— 消息会"附身"到新人。发号取四个来源的最大值：序号文件、`users` 表、`msgs` 表、已注销名册。
- **改名走名册广播**，不洗历史消息。服务端每 10 秒看一次 `roster.json` 的 mtime，变了就广播给所有在线连接。
- **撤回是"删行 + 补一条系统提示"**：服务端先 `DELETE FROM msgs` 删掉原消息，
  再插一条 `sys=1` 的提示行（`tip_ts` = 当前时间），客户端据此在**原位置**显示"XX 撤回了一条消息"。
  所以原消息的正文已经不在了，保住的只是"位置"这个观感，原时间戳并不会保留。
- **归档 ≠ 删除**。归档只对管理端隐藏，用户身份和历史都不动。
- **桌面端聊天视图**已抽成组件 → [`packages/chatview`](../packages/chatview)，服务端和客户端都不依赖它。

## 延伸文档

- [部署方案](yehochat-deploy.md)：systemd、nginx、管理口令、证书
- [协议要点](yehochat-protocol.md)：登录、收发、撤回、名册、身份

## 配置

- `apps/yehochat/server/.env.example` → 复制成 `.env`（服务启动时自动读，零依赖解析器见 `yeho_env.py`）
- `apps/yehochat/keystore.properties.example` → 复制成 `apps/yehochat/keystore.properties`（Android 签名用）

真实配置都在 `.gitignore` 里，不会入库。
