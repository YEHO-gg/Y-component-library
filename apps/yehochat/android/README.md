# Android 客户端

Java + Gradle，minSdk 24。服务器地址**不在源码里**，由 uildConfigField 注入。

## 构建

    在 ~/.gradle/gradle.properties（或项目根 gradle.properties，已 gitignore）里写：
        chatHost=your-domain-or-ip
        chatWsPort=8705
        chatGatePort=8443

    ./gradlew assembleDebug

没有签名密钥也能构建（debug 用默认签名）；要出 release 包就建一个 keystore.properties
（参考仓库根的 keystore.properties.example），它和 *.p12 都在 .gitignore 里。

## 结构

| 文件 | 作用 |
|---|---|
| ChatService.java | WebSocket 长连接、断线重连、心跳 |
| ChatActivity.java | 聊天界面（RecyclerView + 气泡） |
| MsgStore.java | 本地消息缓存与排序 |
| Net.java | 走 nginx 网关的 HTTP 封装（X-Chat-Token 鉴权） |
| LoginActivity / RegisterActivity / ProfileActivity | 账号相关 |
| SettingsActivity | 服务器信息、退出登录等 |
| UpdateChecker.java | 检查更新 |

## 已知限制

- 自签证书信任写在 Tls.java 里，换成正式证书后请删掉那段
- 没有群聊概念，服务端就是一个共享房间