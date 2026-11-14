# chatview —— PySide6 聊天记录视图

`packages/chatview`。装完 `pyside6` 就能跑，没有其它依赖。

```bash
cd packages/chatview
pip install pyside6
python examples/demo.py        # 带假消息的 demo，每 4 秒来一条新消息
```

![preview](../packages/chatview/docs/preview.png)

## 为什么不用 QScrollArea

最初的版本把每条消息做成一个 `QWidget` 塞进 `QScrollArea`，于是：

| 现象 | 原因 | 结果 |
|---|---|---|
| 一格滚轮卡 293ms | 120 个气泡控件整屏重绘，每个 QLabel 重解析一次全局 QSS | 没法用 |
| 往上翻历史"被无形的手拽回底部" | 滚动条按需显示 → 视口变窄 → 气泡重新换行 → 高度变 → 滚动条再切换 | 死循环 |
| 撤回一条消息，整个列表跳一下 | `model.reset()` 让所有行重新布局 | 位置丢失 |

换 `QListView + QAbstractListModel + QStyledItemDelegate` 之后：一格滚轮 14ms（约 70fps），只有可视的十来行被绘制。

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
view.append_message(Message(uid=2, name="她", text="新消息"))
view.show()
app.exec()
```

## API

| 对象 | 作用 |
|---|---|
| `ChatView(my_uid=..., name_of=..., theme="light"\|"dark", separator_gap=300)` | 主控件，含列表 + 浮标 |
| `view.set_messages(list)` | 一次性装载，自动插时间分隔条 |
| `view.append_message(m)` | 追加一条，按"是否在底部"决定滚不滚 |
| `view.replace_message(row, m)` | 原地替换一行（撤回用），不动滚动位置 |
| `view.scroll_to_bottom(force=False)` | 滚到底 |
| `view.is_at_bottom()` | 是否在底部（60px 阈值） |
| `Message(uid, name, text, ts, mine, kind, quote)` | 数据对象；`kind` = `msg` / `time` / `recall` |
| `ChatModel` / `ChatDelegate` | 想自己组装 `QListView` 时直接用 |
| `fmt_separator(ts)` | 今天 / 昨天 / MM-DD / YYYY-MM-DD 的文案 |

`name_of` 是回调 `Message -> str`，用来把 uid 解析成当前昵称（改过名的人，历史消息也要跟着改）。

## 设计取舍

- **不用 QSS 给气泡上色**。颜色全部来自 `theme.py` 的两份 palette，delegate 直接 `QPainter` 画；换主题 = 换 dict 再重绘。
- **几何结果缓存**。`_geom()` 按 `(时间, 发送者, 正文, 是否有引用, 可用宽度)` 缓存 —— 中文换行测量很贵，滚动时每帧都会问。
- **引用块会撑宽气泡**，否则引用被挤成一条窄缝。
- **名称和时间按整行宽度画**，不贴气泡画，否则气泡很窄时出现"5 YEH…"这种断头文字。
- **不管网络和存储**。这个库只负责"给我一批 Message，画出来"。

## 测试

```bash
cd packages/chatview
QT_QPA_PLATFORM=offscreen python tests/run_tests.py     # 不需要 pytest
QT_QPA_PLATFORM=offscreen python -m pytest tests -q
```

4 个用例：时间分隔条、单行替换不改变行数、**"看历史时新消息不能把视图拽到底部"**、分隔条文案。

## 截图

`examples/shot.py` 用 `view.grab()` 离屏出图，CI 里也能跑。

> 坑：offscreen 平台没有字体，中文会渲染成方块。必须先 `QFontDatabase.addApplicationFont("C:/Windows/Fonts/msyh.ttc")`（脚本里已处理）。

## 已知限制

- 只验证了 PySide6 6.12 / Windows；PyQt6 需要替换 `Signal` 导入，未测。
- 不支持富文本：只有纯文本 + emoji。要 Markdown 气泡得自己扩展 delegate 的绘制分支。
- 没有输入框、没有表情面板 —— 只做记录视图。
