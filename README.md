# Y-component-library

自用的一个"组件 + 应用"合集。能单独拿走的放 `packages/`，能整套部署的放 `apps/`，
给 AI 助手用的行为准则放 `skills/`。

---

## 目录

### 🧩 组件 `packages/`

| 部件 | 说明 | 文档 |
|---|---|---|
| [chatview](#chatview) | PySide6 聊天记录视图，几千条消息不卡 | [docs/chatview.md](docs/chatview.md) |

### 🚀 应用 `apps/`

| 部件 | 说明 | 文档 |
|---|---|---|
| [YehoChat](#yehochat) | 自建小圈子聊天：Python 服务端 + Android 客户端 | [docs/yehochat.md](docs/yehochat.md) |
| [CHRONOS 时光](#chronos-时光) | 零依赖在线时钟：世界时间 / 秒表 / 倒计时 | [docs/chronos.md](docs/chronos.md) |

### 🧠 技能 `skills/`

给 AI 编码助手用的**行为准则**（不是能力扩展）。拷进 `~/.dsh/skills/` 即生效，DSH 带文件监听、不用重启。

| 技能 | 什么时候用 | 干什么 |
|---|---|---|
| [grill-me](#grill-me) | 需求含糊、要多文件改动 | 先复述、列空白、拆成带验收标准的步骤 |
| [tdd](#tdd) | 写代码、改逻辑、修 bug | 先写出一个会失败的检查，再让它通过 |
| [triage](#triage) | 报错、行为异常 | 先定位根因再改，别靠猜 |

### 📖 文档 `docs/`

| 文档 | 内容 |
|---|---|
| [chatview 组件用法](docs/chatview.md) | API、设计取舍、测试、截图 |
| [YehoChat 总览](docs/yehochat.md) | 有什么、怎么跑、设计选择 |
| [部署方案](docs/yehochat-deploy.md) | systemd + nginx + 证书 + 管理口令 |
| [协议要点](docs/yehochat-protocol.md) | 登录 / 收发 / 撤回 / 名册 / 身份 |
| [CHRONOS 用法](docs/chronos.md) | 功能、快捷键、文件结构 |
| [CHRONOS 部署](docs/chronos-deploy.md) | Nginx 静态托管、缓存与安全头、HTTPS、子路径 |
| [技能（SKILL.md）](docs/skills.md) | 格式、安装路径、三个技能的正文与由来 |

---

## chatview

一个能扛住几千条消息的聊天记录视图。不是"又一个气泡控件"——它换掉了渲染模型：

- 一格滚轮 **293ms → 14ms**（QScrollArea 堆控件 → QListView + delegate 自绘）
- 撤回时**只重绘一行**，滚动位置不动（曾经整表 reset 会让列表跳一下）
- 新消息**不把你从历史里拽走**，只有在底部时才自动跟随

```bash
cd packages/chatview
pip install -e .                # 必须装成本地包，demo 里是 from ycomponent.chatview import ...
python examples/demo.py
```

```python
from ycomponent.chatview import ChatView, Message

view = ChatView(my_uid=1, name_of=lambda m: m.name, theme="dark")
view.set_messages([Message(uid=1, name="我", text="在吗", mine=True)])
view.append_message(Message(uid=2, name="她", text="在"))
```

![chatview](packages/chatview/docs/preview.png)

细节（几何缓存、时间分隔、引用块、浮标）见 [docs/chatview.md](docs/chatview.md)。

## YehoChat

十几个人、长期住一个房间、不依赖第三方平台。有账号体系、引用/撤回、改名广播、管理端。登录是**单端**的：新设备登录会把旧连接顶下去。

```bash
cd apps/yehochat/server
pip install -r requirements.txt
python gen_cert.py                 # 首次：自签证书
python server.py                   # :8705 聊天（WSS，客户端直连，需对外开放）
python chat_users.py               # :8706 账号与管理（HTTP）
python update_srv.py               # :8811 版本/更新信息（HTTP）
```

Android 端在 `apps/yehochat/android`，服务器地址走 gradle 属性，不写进源码。
完整步骤见 [docs/yehochat-deploy.md](docs/yehochat-deploy.md)，协议见 [docs/yehochat-protocol.md](docs/yehochat-protocol.md)。

---

## CHRONOS 时光

一个安静、克制、零依赖零构建的在线时钟：实时本地时间（含农历、今日进度）、世界时间（本地 + 8 个城市）、百分秒秒表、倒计时、专注视图。**页面不请求任何外部资源**——首次加载后断网仍能继续运行（没有 Service Worker，断网后刷新不在保证范围内）。

    cd apps/chronos
    python -m http.server 8000

预览图：`apps/chronos/preview.png`。部署是纯静态：Nginx 配置在 `apps/chronos/deploy/nginx.conf`，步骤见 [docs/chronos-deploy.md](docs/chronos-deploy.md)。

## grill-me

动手前先问清楚。用在需求含糊、要改多个文件、或者对方只丢来一句话的时候。

四步：一句话复述 → 列出必须问清的空白（**最多 3 个，能自己查到的不要问**）→ 拆步骤、每步写验收标准 → 不可逆的步骤单独标出来先确认。

写不出验收标准，就说明这步还没想清楚。

## tdd

先说清怎么验证，再动手写。

核心一句：**在写实现之前，先写出一个此刻会失败的检查。** 没红过的检查，你不知道它在测什么，也不知道它会不会永远绿。

汇报必须给三样：检查是什么、改之前真的红过（贴失败输出）、改之后是绿的（贴通过输出）。

## triage

先定位，再动手。改代码之前先回答：**我确切知道是哪一行、哪个条件出的问题吗？**

拿到原文证据 → 先复现 → 写至少两个假设、每个配一个能证伪它的实验 → 二分收窄 → 区分现象和原因 → 找到根因才改 → 用同一套复现步骤验证。

只列一个假设然后去找支持它的证据，那叫确认偏误。

三份正文都在 `skills/<name>/SKILL.md`，装法与由来见 [docs/skills.md](docs/skills.md)。

## 仓库结构

```
packages/                 可复用组件（pip 装得走的那种）
└─ chatview/              PySide6 聊天记录视图
   ├─ ycomponent/         源码（import ycomponent.chatview）
   ├─ examples/           demo + 离屏截图脚本
   ├─ tests/              headless 冒烟测试
   └─ pyproject.toml

apps/                     完整应用（要部署的）
├─ yehochat/              自建小圈子聊天
│  ├─ server/             Python 服务端（3 个进程）
│  └─ android/            Android 客户端
└─ chronos/               CHRONOS 在线时钟
   ├─ index.html          页面
   ├─ styles.css / app.js 样式与逻辑
   └─ deploy/nginx.conf   Nginx 配置

skills/                   给 AI 助手的行为准则（SKILL.md）
├─ grill-me/             先问清再拆步骤
├─ tdd/                  先写会失败的检查
└─ triage/               先定位根因再改

docs/                     文档（见上方索引）
```

## 配置

仓库只带示例：`apps/yehochat/server/.env.example`、`apps/yehochat/keystore.properties.example`。复制一份填自己的值即可，真实配置在 `.gitignore` 里，不会入库。

## 测试

```bash
cd packages/chatview
pip install -e ".[dev]"                                # 或 pip install -e . pytest
python tests/run_tests.py                              # 4 个用例，不需要 pytest
python -m pytest tests -q                              # 同一批用例走 pytest
```

两个脚本内部都会 `setdefault("QT_QPA_PLATFORM", "offscreen")`，不用手动加环境变量前缀
（PowerShell 里真要加的话写成 `$env:QT_QPA_PLATFORM="offscreen"; python ...`）。

CI 在 `.github/workflows/test.yml`：**windows-latest + Python 3.12**，只跑 `python -m pytest tests -q`，
且只覆盖 `packages/chatview`。`apps/yehochat` 和 `apps/chronos` 目前没有任何 CI。
Python 版本要求：`packages/chatview` 是 `>=3.10`（见 `pyproject.toml`），仓库其余部分是脚本级，无版本声明。

## License

MIT
