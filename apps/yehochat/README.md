# YehoChat

自建小圈子聊天：Python 服务端 + Android 客户端。目标很窄——**十几个人、长期住在一个房间、不依赖任何第三方平台**。

## 组成

| 目录 | 内容 |
|---|---|
| [`server/`](server/) | 三个进程：聊天 WSS `:8705`、账号与管理 `:8706`、更新信息 `:8811` |
| [`android/`](android/) | Java 客户端，服务器地址走 gradle 属性 |
| [`keystore.properties.example`](keystore.properties.example) | Android 签名配置模板（真实文件不进仓库） |

## 快速开始（服务端）

```bash
cd server
pip install -r requirements.txt
cp .env.example .env         # 可选：填 SMTP，注册验证码要用
python gen_cert.py           # 首次：自签证书
python server.py             # :8705
python chat_users.py         # :8706
python update_srv.py         # :8811
```

数据统一落在 `server/data/`（可用 `YEHOO_CHAT_DIR` 改）：
`chat.db`（消息 + 账号 + 管理口令）、日志、`uid_seq`、`cert/`、`roster.json`。

## 客户端

```bash
cd android
cp gradle.properties.example gradle.properties   # 填 chatHost=你的域名或IP
./gradlew assembleDebug
```

## 文档

- [总览](../../docs/yehochat.md)
- [部署方案](../../docs/yehochat-deploy.md)（systemd / nginx / 管理口令 / 证书）
- [协议要点](../../docs/yehochat-protocol.md)

## License

MIT
