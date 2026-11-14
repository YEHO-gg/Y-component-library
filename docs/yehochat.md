# YehoChat —— 自建小圈子聊天

`apps/yehochat`。一个 Python 服务端 + 一个 Android 客户端，自己部署、自己用。

不是微信替代品，目标很窄：**十几个人、要长期住在一个房间里、不依赖任何第三方平台**。

## 它有什么

- **账号**：注册 / 登录 / 改密码 / 改昵称 / 改 uuid（30 天一次），邮箱验证码找回
- **聊天**：WebSocket 长连接、引用回复、撤回、按时间稳定排序、未读与"跳到最新"
- **身份**：不可变 `uid` + 用户可改的 `uuid`；改名后所有历史消息跟着显示新名字（名册广播）
- **多设备**：同一账号可同时在线多个连接，不互相踢
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

python gen_cert.py            # 首次：自签证书（要 pip install cryptography）
python server.py              # :8705
python chat_users.py          # :8706
```

数据默认落在 `server/data/`（数据库、名册、证书、日志），可用 `YEHOO_CHAT_DIR` 改。
表结构见 [`server/schema.sql`](../apps/yehochat/server/schema.sql)。

Android：

```bash
cd apps/yehochat/android
# 服务器地址别写进代码，写到 ~/.gradle/gradle.properties：
#   chatHost=你的域名或IP
./gradlew assembleDebug
```

## 设计上几个刻意的选择

- **uid 只增不复用**。注销掉最大号后如果按 `max(uid)+1` 发号，下一个注册者会拿到同一个号，而老消息还挂在那个号上 —— 消息会"附身"到新人。发号取四个来源的最大值：序号文件、`users` 表、`msgs` 表、已注销名册。
- **改名走名册广播**，不洗历史消息。服务端每 10 秒看一次 `roster.json` 的 mtime，变了就广播给所有在线连接。
- **撤回不删行**，改写成一条系统提示并保留原时间戳，这样它留在原位。
- **归档 ≠ 删除**。归档只对管理端隐藏，用户身份和历史都不动。
- **桌面端聊天视图**已抽成组件 → [`packages/chatview`](../packages/chatview)，服务端和客户端都不依赖它。

## 延伸文档

- [部署方案](yehochat-deploy.md)：systemd、nginx、管理口令、证书
- [协议要点](yehochat-protocol.md)：登录、收发、撤回、名册、身份

## 安全

仓库里没有任何真实凭据。部署时自己生成一套，别复用示例值。
