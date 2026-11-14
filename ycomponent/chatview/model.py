"""Data model for the chat view.

`Message` is a plain dataclass — the delegate never reaches into your storage
layer, it only reads what is on the message object. Convert your own rows with
`Message.from_dict` if you already have dicts.
"""

from __future__ import annotations

import time
from dataclasses import dataclass, field
from typing import Any, Callable

from PySide6.QtCore import QAbstractListModel, QModelIndex, Qt

# Role used to hand the whole Message object to the delegate.
ROLE_MSG = int(Qt.UserRole) + 1


@dataclass
class Message:
    """One row in the chat.

    Attributes
    ----------
    uid:      stable sender id (never reuse ids — see README "postmortem")
    name:     display name snapshot, used when the id cannot be resolved
    text:     body text
    ts:       unix seconds (float ok)
    mine:     True for "my" messages (right aligned)
    kind:     ``"msg"`` (default) | ``"time"`` (separator) | ``"recall"``
    quote:    optional ``Message``-like object being replied to
    """

    uid: int | str = 0
    name: str = ""
    text: str = ""
    ts: float = field(default_factory=lambda: time.time())
    mine: bool = False
    kind: str = "msg"
    quote: Any | None = None

    @classmethod
    def from_dict(cls, row: dict[str, Any], *, my_uid: int | str | None = None) -> "Message":
        quote = row.get("quote")
        return cls(
            uid=row.get("uid", 0),
            name=row.get("name", ""),
            text=row.get("text", "") or "",
            ts=row.get("ts", time.time()),
            mine=bool(row.get("mine", my_uid is not None and row.get("uid") == my_uid)),
            kind=row.get("kind", "msg"),
            quote=quote,
        )

    @property
    def is_separator(self) -> bool:
        return self.kind == "time"

    @property
    def is_recall(self) -> bool:
        return self.kind == "recall"


class ChatModel(QAbstractListModel):
    """Thin list model: holds Messages, exposes them through ROLE_MSG."""

    def __init__(self, parent=None) -> None:
        super().__init__(parent)
        self.items: list[Message] = []

    # -- Qt plumbing -------------------------------------------------------
    def rowCount(self, parent: QModelIndex = QModelIndex()) -> int:  # noqa: N802
        return 0 if parent.isValid() else len(self.items)

    def data(self, index: QModelIndex, role: int = Qt.DisplayRole):
        if not index.isValid() or index.row() >= len(self.items):
            return None
        if role == ROLE_MSG:
            return self.items[index.row()]
        return None

    # -- mutation ----------------------------------------------------------
    def reset_items(self, msgs: list[Message]) -> None:
        self.beginResetModel()
        self.items = list(msgs)
        self.endResetModel()

    def append(self, m: Message) -> None:
        row = len(self.items)
        self.beginInsertRows(QModelIndex(), row, row)
        self.items.append(m)
        self.endInsertRows()

    def replace(self, row: int, m: Message) -> None:
        """Replace one row in place — no reset, so scroll position survives.

        This is the fix for the "recall makes the whole list jump" bug: resetting
        the model re-lays-out every row and the view snaps back.
        """
        if not 0 <= row < len(self.items):
            return
        self.items[row] = m
        idx = self.index(row, 0)
        self.dataChanged.emit(idx, idx)

    def row_of(self, predicate: Callable[[Message], bool]) -> int | None:
        for i, m in enumerate(self.items):
            if predicate(m):
                return i
        return None
