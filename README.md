# Y-component-library

PySide6 组件库。目前有一个组件：**`ycomponent.chatview`** —— 一个能扛住几千条消息的聊天记录视图。

![preview](docs/preview.png)

## 为什么会有这个

不是"又一个聊天气泡控件"。它是从一个真实桌面聊天客户端里抠出来的，那个客户端最早的版本把每条消息都做成一个 `QWidget` 塞进 `QScrollArea`，于是：

| 现象 | 原因 | 结果 |
|---|---|---|
| 一格滚轮卡 293ms | 120 个气泡控件整屏重绘，每个 QLabel 都重解析一次全局 QSS | 没法用 |
| 往上翻历史"被无形的手拽回底部" | 滚动条按需显示 → 视口变窄 → 气泡重新换行 → 高度变 → 滚动条再切换 | 死循环 |
| 撤回一条消息，整个列表跳一下 | `model.reset()` 让所有行重新布局 | 位置丢失 |

换成 `QListView + QAbstractListModel + QStyledItemDelegate` 之后：

- **一格滚轮 14ms**（约 70fps），只有可视的十来行会被绘制
- 撤回改成 `dataChanged` 单行更新，滚动位置不动
- 自动滚到底只在"你本来就在底部"时发生（60px 阈值），否则出现"N 条新消息 ↓"的浮标

这些不是微调出来的，是换掉了整个渲染模型。所以这个库把"换掉之后怎么写"直接做好给你。

## 安装

```bash
pip install pyside6
# 把本仓库放到你的项目里，或
pip install -e .
```

## 用法

```python
from PySide6.QtWidgets import QApplication
from ycomponent.chatview import ChatView, Message

app = QApplication([])
view = ChatView(my_uid=1, name_of=lambda m: m.name, theme="dark")
view.set_messages([
    Message(uid=1, name="我", text="在吗", mine=True),
    Message(uid=2, name="她", text="在", ts=...),
])
view.append_message(Message(uid=2, name="她", text="新消息"))  # 你在底部才会自动滚下去
view.show()
app.exec()
```

完整可跑的 demo：

```bash
python examples/demo.py
```

## API

| 对象 | 作用 |
|---|---|
| `ChatView(my_uid=..., name_of=..., theme="light"\|"dark")` | 主控件，含列表 + 浮标 |
| `view.set_messages(list)` | 一次性装载，自动插时间分隔条 |
| `view.append_message(m)` | 追加一条，按"是否在底部"决定滚不滚 |
| `view.replace_message(row, m)` | 原地替换一行（撤回用），不动滚动位置 |
| `view.scroll_to_bottom(force=False)` | 滚到底 |
| `Message(uid, name, text, ts, mine, kind, quote)` | 数据对象；`kind` = `msg` / `time` / `recall` |
| `ChatDelegate` | 想自己组装 `QListView` 时直接用 |
| `fmt_separator(ts)` | 今天/昨天/MM-DD/YYYY-MM-DD 的文案 |

`name_of` 是个回调：`Message -> str`，用来把 uid 解析成当前昵称（改过名的人，历史消息也要跟着改）。

## 设计取舍

- **不用 QSS 给气泡上色**。所有颜色来自 `theme.py` 的两份 palette，delegate 里直接 `QPainter` 画。改主题 = 换 dict 再重绘，不需要给每个控件重新下发样式表。
- **几何结果缓存**。`_geom()` 按 `(时间, 发送者, 正文, 是否有引用, 可用宽度)` 缓存，中文换行测量很贵，滚动时每帧都会问一次。
- **引用块会撑宽气泡**。引用比正文长时，气泡跟着变宽，否则引用被挤成一条窄缝。
- **名称和时间按整行宽度画**，不贴气泡画，否则气泡很窄时会出现"5 YEH…"这种断头文字。
- **不自己管网络/存储**。这个库只负责"给我一批 Message，画出来"。

## 测试

```bash
pip install pytest
QT_QPA_PLATFORM=offscreen pytest tests -q
# 或者不装 pytest：
QT_QPA_PLATFORM=offscreen python tests/run_tests.py
```

4 个用例覆盖：时间分隔条、单行替换不改变行数、**"在看历史时新消息不能把视图拽到底部"**。

## 已知限制

- 只验证了 PySide6（6.12）在 Windows 上；PyQt6 需要把 `Signal`/`slot` 的导入换掉，没测。
- 不用 `QTextDocument` 做富文本：只有纯文本 + emoji。要 Markdown 气泡得自己扩展 delegate 的 `drawText` 分支。
- 没有输入框、没有表情面板——这个库只做"记录视图"。

## License

MIT
