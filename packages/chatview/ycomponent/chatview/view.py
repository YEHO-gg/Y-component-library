"""ChatView — QListView + delegate + the scroll behaviour that people notice.

Three behaviours matter far more than the visual style:

1. **Auto-scroll only when already at the bottom.** If you are reading history and
   a message arrives, the view must NOT yank you down. Measured with a 60 px
   threshold (anything closer counts as "at bottom").
2. **Recall updates one row, not the whole model.** A model reset re-lays-out
   every row and the scrollbar jumps; `ChatModel.replace` emits dataChanged for
   a single row instead.
3. **A floating pill for new messages while scrolled up**, so the user can jump
   back deliberately instead of being dragged.
"""

from __future__ import annotations

import time
from typing import Callable, Iterable

from PySide6.QtCore import QEvent, QObject, Qt, Signal
from PySide6.QtWidgets import (
    QAbstractItemView,
    QFrame,
    QHBoxLayout,
    QLabel,
    QListView,
    QPushButton,
    QVBoxLayout,
    QWidget,
)

from .delegate import ChatDelegate, NameResolver, fmt_separator
from .model import ChatModel, Message
from .theme import DARK, THEMES, LIGHT

# Insert a time separator when the gap exceeds this many seconds.
SEPARATOR_GAP = 300
# Anything within this many pixels of the bottom counts as "at the bottom".
BOTTOM_EPSILON = 60


class JumpPill(QFrame):
    """Floating 'N new messages ↓' pill, bottom-right of the view."""

    clicked = Signal()

    def __init__(self, parent: QWidget) -> None:
        super().__init__(parent)
        self.setObjectName("JumpPill")
        self.setCursor(Qt.PointingHandCursor)
        lay = QHBoxLayout(self)
        lay.setContentsMargins(12, 6, 12, 6)
        self.lbl = QLabel("")
        self.lbl.setObjectName("JumpPillLabel")
        lay.addWidget(self.lbl)
        self.hide()

    def set_state(self, unread: int) -> None:
        if unread <= 0:
            self.hide()
            return
        self.lbl.setText(f"{unread} 条新消息  ↓")
        self.show()
        self.raise_()

    def mousePressEvent(self, event) -> None:  # noqa: N802
        self.clicked.emit()
        super().mousePressEvent(event)


class ChatView(QWidget):
    """Drop-in chat transcript widget.

    >>> view = ChatView(my_uid=1, name_of=lambda m: m.name)
    >>> view.set_messages([Message(uid=1, name="me", text="hi", mine=True)])
    """

    messageClicked = Signal(object)
    atBottomChanged = Signal(bool)

    def __init__(
        self,
        parent: QWidget | None = None,
        *,
        my_uid: int | str | None = None,
        name_of: NameResolver | None = None,
        theme: str = "light",
        separator_gap: int = SEPARATOR_GAP,
    ) -> None:
        super().__init__(parent)
        self.my_uid = my_uid
        self._name_of = name_of
        self._separator_gap = separator_gap
        self._unread = 0
        self._force_bottom = True

        self.model = ChatModel(self)
        self.delegate = ChatDelegate(self, name_of=name_of)

        self.view = QListView(self)
        self.view.setObjectName("ChatViewList")
        self.view.setModel(self.model)
        self.view.setItemDelegate(self.delegate)
        self.view.setVerticalScrollMode(QAbstractItemView.ScrollPerPixel)
        self.view.setHorizontalScrollBarPolicy(Qt.ScrollBarAlwaysOff)
        self.view.setFrameShape(QFrame.NoFrame)
        self.view.setSelectionMode(QAbstractItemView.NoSelection)
        self.view.setFocusPolicy(Qt.NoFocus)
        self.view.verticalScrollBar().setSingleStep(24)
        self.view.setSpacing(0)
        self.view.viewport().installEventFilter(self)

        lay = QVBoxLayout(self)
        lay.setContentsMargins(0, 0, 0, 0)
        lay.addWidget(self.view)

        self.pill = JumpPill(self.view.viewport())
        self.pill.clicked.connect(self.scroll_to_bottom)
        self.view.verticalScrollBar().valueChanged.connect(self._on_scrolled)

        self.set_theme(theme)

    # -- theme -------------------------------------------------------------
    def set_theme(self, theme: str) -> None:
        pal = THEMES.get(theme, LIGHT)
        self.delegate.set_palette(pal)
        self.view.setStyleSheet(
            f"QListView#ChatViewList {{ background: {pal['bg']}; border: none; }}"
        )
        self.pill.setStyleSheet(
            f"QFrame#JumpPill {{ background: {pal['surface']}; border: 1px solid {pal['line']};"
            f" border-radius: 14px; }}"
            f" QLabel#JumpPillLabel {{ color: {pal['text']}; font-size: 12px; }}"
        )
        self.view.viewport().update()

    # -- data --------------------------------------------------------------
    def set_messages(self, msgs: Iterable[Message], *, with_separators: bool = True) -> None:
        items = list(msgs)
        if with_separators:
            items = self._with_separators(items)
        self._force_bottom = True
        self.model.reset_items(items)
        self.delegate.invalidate()
        self._unread = 0
        self.pill.set_state(0)
        self.scroll_to_bottom(force=True)

    def append_message(self, m: Message) -> None:
        """Append one message; auto-scrolls only if the user was at the bottom."""
        was_bottom = self.is_at_bottom()
        if self._separator_gap and self.model.items:
            prev = self.model.items[-1]
            if (not prev.is_separator) and (m.ts - prev.ts) >= self._separator_gap:
                self.model.append(Message(kind="time", text=fmt_separator(m.ts), ts=m.ts))
        self.model.append(m)
        if was_bottom:
            self.scroll_to_bottom(force=True)
        else:
            self._unread += 1
            self.pill.set_state(self._unread)

    def replace_message(self, row: int, m: Message) -> None:
        """In-place row update — used for recalls; keeps the scroll position."""
        self.model.replace(row, m)

    # -- scrolling ---------------------------------------------------------
    def is_at_bottom(self) -> bool:
        bar = self.view.verticalScrollBar()
        return bar.value() >= bar.maximum() - BOTTOM_EPSILON

    def scroll_to_bottom(self, *, force: bool = False) -> None:
        bar = self.view.verticalScrollBar()
        bar.setValue(bar.maximum())
        if force or self.is_at_bottom():
            self._unread = 0
            self.pill.set_state(0)

    def _on_scrolled(self, _value: int) -> None:
        bottom = self.is_at_bottom()
        if bottom:
            self._unread = 0
            self.pill.set_state(0)
        self.atBottomChanged.emit(bottom)

    # -- helpers -----------------------------------------------------------
    def _with_separators(self, items: list[Message]) -> list[Message]:
        if not self._separator_gap:
            return items
        out: list[Message] = []
        last_ts: float | None = None
        for m in items:
            if m.is_separator:
                out.append(m)
                last_ts = m.ts
                continue
            if last_ts is None or (m.ts - last_ts) >= self._separator_gap:
                out.append(Message(kind="time", text=fmt_separator(m.ts), ts=m.ts))
            out.append(m)
            last_ts = m.ts
        return out

    def eventFilter(self, obj: QObject, event: QEvent) -> bool:  # noqa: N802
        if obj is self.view.viewport() and event.type() == QEvent.Resize:
            self._place_pill()
        return super().eventFilter(obj, event)

    def _place_pill(self) -> None:
        if not self.pill.isVisible():
            return
        self.pill.adjustSize()
        vp = self.view.viewport()
        x = vp.width() - self.pill.width() - 18
        y = vp.height() - self.pill.height() - 18
        self.pill.move(max(0, x), max(0, y))
