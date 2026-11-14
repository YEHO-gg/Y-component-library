"""Headless smoke test — runs without a display (offscreen platform).

    QT_QPA_PLATFORM=offscreen python -m pytest tests -q
"""

from __future__ import annotations

import os

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PySide6.QtWidgets import QApplication  # noqa: E402

from ycomponent.chatview import ChatView, Message, fmt_separator  # noqa: E402

_app = QApplication.instance() or QApplication([])


def _view() -> ChatView:
    return ChatView(my_uid=1, name_of=lambda m: m.name or str(m.uid))


def test_separator_format() -> None:
    import time as _t

    now = _t.time()
    assert fmt_separator(now).startswith("今天 ")
    assert fmt_separator(now - 86400).startswith("昨天 ")


def test_model_rows_and_separators() -> None:
    v = _view()
    base = 1_700_000_000.0
    v.set_messages([
        Message(uid=2, name="a", text="hi", ts=base),
        Message(uid=2, name="a", text="again", ts=base + 60),      # no separator
        Message(uid=1, name="me", text="later", ts=base + 3600, mine=True),  # separator
    ])
    kinds = [m.kind for m in v.model.items]
    assert kinds.count("time") == 2          # one at the top, one for the 1h gap
    assert len(v.model.items) == 5


def test_recall_replaces_row_in_place() -> None:
    v = _view()
    base = 1_700_000_000.0
    v.set_messages([Message(uid=2, name="a", text="oops", ts=base)])
    rows_before = v.model.rowCount()
    row = v.model.row_of(lambda m: m.text == "oops")
    assert row is not None
    v.replace_message(row, Message(uid=2, name="a", text="", ts=base, kind="recall"))
    assert v.model.rowCount() == rows_before
    assert v.model.items[row].kind == "recall"


def test_append_does_not_scroll_when_reading_history() -> None:
    v = _view()
    v.resize(600, 300)
    v.show()                      # offscreen platform still needs a layout pass
    _app.processEvents()
    base = 1_700_000_000.0
    v.set_messages([
        Message(uid=2, name="a", text=f"消息 {i}", ts=base + i * 10)
        for i in range(200)
    ])
    _app.processEvents()
    v.scroll_to_bottom(force=True)
    bar = v.view.verticalScrollBar()
    assert bar.maximum() > 0, "the view did not lay out — no scroll range"
    assert v.is_at_bottom()
    bar.setValue(0)                      # scroll up to history
    assert not v.is_at_bottom()
    v.append_message(Message(uid=2, name="a", text="new", ts=base + 5000))
    assert bar.value() == 0              # must not be dragged to the bottom
    assert v._unread == 1
