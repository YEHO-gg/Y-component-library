"""Demo: run `python examples/demo.py`.

Left: a fake conversation with quotes, a recall and time separators.
It fakes an incoming message every 4 seconds so you can see the
"don't yank me down" behaviour — scroll up and watch the pill appear.
"""

from __future__ import annotations

import sys
import time

from PySide6.QtCore import QTimer
from PySide6.QtWidgets import QApplication, QPushButton, QVBoxLayout, QWidget

from ycomponent.chatview import ChatView, Message


def demo_messages() -> list[Message]:
    base = time.time() - 3600
    convo = [
        ("me", "在吗", True),
        ("她", "在", False),
        ("me", "问你个事", True),
        ("她", "说", False),
        ("me", "这个聊天列表滚起来卡不卡", True),
        ("她", "卡，滚一格要半天", False),
        ("me", "那是控件太多，得换 delegate 自绘", True),
        ("她", "听不懂", False),
        ("me", "就是别一个气泡一个控件，改成只画看得见的那几条", True),
    ]
    out: list[Message] = []
    for i, (name, text, mine) in enumerate(convo):
        out.append(Message(uid=1 if mine else 2, name=name, text=text,
                           ts=base + i * 40, mine=mine))
    # one quoted reply
    out.append(Message(uid=2, name="她", text="所以到底能快多少",
                       ts=base + 400, quote=out[-1]))
    out.append(Message(uid=1, name="me", text="293ms 一格 → 14ms 一格",
                       ts=base + 460, mine=True))
    # a recall placeholder
    out.append(Message(uid=2, name="她", text="", ts=base + 520, kind="recall"))
    # a gap > 5 min so a separator is inserted by the view
    out.append(Message(uid=2, name="她", text="行，我去洗澡了", ts=base + 1500))
    return out


def main() -> int:
    app = QApplication(sys.argv)
    win = QWidget()
    win.setWindowTitle("ycomponent.chatview demo")
    win.resize(720, 620)
    lay = QVBoxLayout(win)
    lay.setContentsMargins(0, 0, 0, 0)

    names = {1: "我", 2: "她"}
    view = ChatView(my_uid=1, name_of=lambda m: m.name or names.get(m.uid, "?"), theme="dark")
    view.set_messages(demo_messages())
    lay.addWidget(view, 1)

    btn = QPushButton("切换主题 light/dark")
    themes = ["dark", "light"]
    state = {"i": 0}

    def toggle() -> None:
        state["i"] = (state["i"] + 1) % 2
        view.set_theme(themes[state["i"]])

    btn.clicked.connect(toggle)
    lay.addWidget(btn)

    # fake incoming traffic
    state["n"] = 0

    def tick() -> None:
        state["n"] += 1
        view.append_message(
            Message(uid=2, name="她", text=f"第 {state['n']} 条新消息，看看它会不会把你拽下去",
                    ts=time.time())
        )

    timer = QTimer(win)
    timer.timeout.connect(tick)
    timer.start(4000)

    win.show()
    return app.exec()


if __name__ == "__main__":
    raise SystemExit(main())
