"""QStyledItemDelegate that paints chat bubbles with QPainter.

Why a delegate and not widgets: a QScrollArea with one QWidget per bubble
re-lays-out and repaints the whole screen on every scroll tick (measured
293 ms per wheel notch with ~120 bubbles on Win10/Qt6). With a list model +
delegate only the ~10 visible rows are painted, and a geometry cache keeps
QFontMetrics from re-measuring wrapped CJK text every frame (14 ms per notch).

The numbers in the docstring above are from the original app this was extracted
from; they are why the cache and the delegate exist at all.
"""

from __future__ import annotations

import time
from typing import Callable

from PySide6.QtCore import QEvent, QModelIndex, QRectF, QSize, Qt
from PySide6.QtGui import QColor, QFont, QFontMetrics, QPainter
from PySide6.QtWidgets import QStyledItemDelegate, QStyleOptionViewItem

from .model import ROLE_MSG, Message
from .theme import LIGHT

NameResolver = Callable[[Message], str]


def default_name_of(m: Message) -> str:
    return m.name or (str(m.uid) if m.uid else "?")


def fmt_separator(ts: float, *, now: float | None = None) -> str:
    """'今天 12:34' / '昨天 12:34' / '11-14 12:34' / '2025-11-14 12:34'."""
    now = time.time() if now is None else now
    lt = time.localtime(ts)
    today = time.localtime(now)
    yday = time.localtime(now - 86400)
    if (lt.tm_year, lt.tm_yday) == (today.tm_year, today.tm_yday):
        return "今天 %02d:%02d" % (lt.tm_hour, lt.tm_min)
    if (lt.tm_year, lt.tm_yday) == (yday.tm_year, yday.tm_yday):
        return "昨天 %02d:%02d" % (lt.tm_hour, lt.tm_min)
    if lt.tm_year == today.tm_year:
        return "%02d-%02d %02d:%02d" % (lt.tm_mon, lt.tm_mday, lt.tm_hour, lt.tm_min)
    return "%d-%02d-%02d %02d:%02d" % (lt.tm_year, lt.tm_mon, lt.tm_mday, lt.tm_hour, lt.tm_min)


def text_height(fm: QFontMetrics, text: str, width: float) -> float:
    if not text:
        return 0
    rect = fm.boundingRect(
        0, 0, max(40, int(width)), 10000,
        int(Qt.TextWordWrap) | int(Qt.AlignLeft) | int(Qt.AlignTop), text,
    )
    return rect.height()


class ChatDelegate(QStyledItemDelegate):
    """Paints bubbles, name+time headers, quote blocks, separators and recalls."""

    PAD_L = 18
    PAD_R = 18
    AVATAR = 34
    GAP = 10
    MAX_BUBBLE = 520
    PAD_X = 12
    PAD_Y = 9

    def __init__(self, parent=None, *, palette: dict[str, str] | None = None,
                 name_of: NameResolver | None = None) -> None:
        super().__init__(parent)
        self.pal = dict(palette or LIGHT)
        self.name_of = name_of or default_name_of
        self._cache: dict[tuple, tuple] = {}

        self.f_name = QFont()
        self.f_name.setFamilies(["Microsoft YaHei UI", "Segoe UI Emoji", "Noto Sans CJK SC"])
        self.f_name.setPointSizeF(8.5)
        self.f_body = QFont()
        self.f_body.setFamilies(["Microsoft YaHei UI", "Segoe UI Emoji", "Noto Sans CJK SC"])
        self.f_body.setPointSizeF(10)
        self.f_small = QFont(self.f_name)
        self.f_small.setPointSizeF(7.5)

    # -- public ------------------------------------------------------------
    def set_palette(self, palette: dict[str, str]) -> None:
        self.pal = dict(palette)
        self._cache.clear()

    def invalidate(self) -> None:
        self._cache.clear()

    # -- metrics -----------------------------------------------------------
    def _geom(self, m: Message, avail: float) -> tuple:
        """(item_h, bubble_w, text_h, quote_h, name_h), cached per (msg, width).

        The cache is the difference between 14 ms and 40+ ms per scroll step:
        wrapped CJK measurement is expensive and it is asked every frame.
        """
        q = m.quote
        key = (m.ts, m.uid, m.text, m.mine, bool(q), avail)
        hit = self._cache.get(key)
        if hit is not None:
            return hit

        fb = QFontMetrics(self.f_body)
        fs = QFontMetrics(self.f_small)
        maxb = max(160, min(self.MAX_BUBBLE, avail - self.PAD_L - self.PAD_R - self.AVATAR - self.GAP - 20))

        tw = fb.horizontalAdvance(m.text) + 2
        # Floor of one character: otherwise a single emoji collapses the bubble.
        bw = max(
            self.PAD_X * 2 + fb.horizontalAdvance("口") + 4,
            min(maxb, tw + self.PAD_X * 2),
        )

        quote_h = 0.0
        if q is not None:
            qname = self.name_of(q) if isinstance(q, Message) else str(getattr(q, "name", "") or "")
            qtext = str(getattr(q, "text", "") or "")[:90]
            qline = "┃ %s：%s" % (qname, qtext)
            qw = fs.horizontalAdvance(qline) + self.PAD_X * 2 + 16
            bw = max(bw, min(maxb, qw))
            quote_h = text_height(fs, qline, bw - self.PAD_X * 2 - 8) + 10

        text_h = text_height(fb, m.text, bw - self.PAD_X * 2)
        name_h = fs.height() + 2
        inner = quote_h + (6 if quote_h else 0) + text_h + self.PAD_Y * 2
        item_h = 8 + name_h + 4 + inner + 8

        out = (max(28.0, item_h), bw, text_h, quote_h, name_h)
        if len(self._cache) > 4000:
            self._cache.clear()
        self._cache[key] = out
        return out

    # -- Qt overrides ------------------------------------------------------
    def sizeHint(self, option: QStyleOptionViewItem, index: QModelIndex) -> QSize:  # noqa: N802
        m: Message | None = index.data(ROLE_MSG)
        if m is None:
            return QSize(10, 10)
        avail = option.rect.width() if option.rect.width() > 50 else 400
        if m.is_separator or m.is_recall:
            return QSize(int(avail), 34)
        h, *_ = self._geom(m, avail)
        return QSize(int(avail), int(h))

    def paint(self, painter: QPainter, option: QStyleOptionViewItem, index: QModelIndex) -> None:  # noqa: C901
        m: Message | None = index.data(ROLE_MSG)
        if m is None:
            return
        pal = self.pal
        painter.save()
        painter.setRenderHint(QPainter.Antialiasing, True)
        rect = QRectF(option.rect)
        painter.setClipRect(rect)

        if m.is_separator:
            painter.setFont(self.f_small)
            painter.setPen(QColor(pal["muted"]))
            painter.drawText(rect, Qt.AlignCenter, m.text or fmt_separator(m.ts))
            painter.restore()
            return

        if m.is_recall:
            painter.setFont(self.f_small)
            text = "%s 撤回了一条消息" % self.name_of(m)
            fm = painter.fontMetrics()
            w = fm.horizontalAdvance(text) + 26
            box = QRectF(rect.center().x() - w / 2, rect.center().y() - 11, w, 22)
            painter.setPen(Qt.NoPen)
            painter.setBrush(QColor(pal["chip"]))
            painter.drawRoundedRect(box, 10, 10)
            painter.setPen(QColor(pal["muted"]))
            painter.drawText(box, Qt.AlignCenter, text)
            painter.restore()
            return

        item_h, bw, text_h, quote_h, name_h = self._geom(m, rect.width())
        mine = m.mine
        top = rect.top() + 8
        if mine:
            bx = rect.right() - self.PAD_R - self.AVATAR - self.GAP - bw
        else:
            bx = rect.left() + self.PAD_L + self.AVATAR + self.GAP

        # avatar
        ax = (rect.right() - self.PAD_R - self.AVATAR) if mine else (rect.left() + self.PAD_L)
        name = self.name_of(m)
        painter.setPen(Qt.NoPen)
        painter.setBrush(QColor(pal["accent_soft"]))
        painter.drawEllipse(QRectF(ax, top, self.AVATAR, self.AVATAR))
        painter.setPen(QColor(pal["accent"]))
        af = QFont(self.f_name)
        af.setPointSizeF(11)
        af.setBold(True)
        painter.setFont(af)
        painter.drawText(QRectF(ax, top, self.AVATAR, self.AVATAR), Qt.AlignCenter,
                         (name or "?")[:1].upper())

        # name + time: drawn across the whole row, never clipped to the bubble
        painter.setFont(self.f_small)
        painter.setPen(QColor(pal["muted"]))
        tm = time.strftime("%H:%M", time.localtime(m.ts))
        label = ("%s  %s" % (tm, name)) if mine else ("%s  %s" % (name, tm))
        if mine:
            rx = ax - self.GAP
            lr = QRectF(rect.left() + self.PAD_L, top,
                        max(40.0, rx - (rect.left() + self.PAD_L)), name_h)
            painter.drawText(lr, Qt.AlignRight | Qt.AlignVCenter, label)
        else:
            lx = ax + self.AVATAR + self.GAP
            lr = QRectF(lx, top, max(40.0, rect.right() - self.PAD_R - lx), name_h)
            painter.drawText(lr, Qt.AlignLeft | Qt.AlignVCenter, label)

        # bubble
        by = top + name_h + 4
        bubble = QRectF(bx, by, bw, quote_h + (6 if quote_h else 0) + text_h + self.PAD_Y * 2)
        painter.setBrush(QColor(pal["mine"] if mine else pal["other"]))
        painter.setPen(Qt.NoPen if mine else QColor(pal["line"]))
        painter.drawRoundedRect(bubble, 10, 10)

        y = by + self.PAD_Y
        if quote_h:
            qr = QRectF(bx + self.PAD_X, y, bw - self.PAD_X * 2, quote_h)
            painter.setBrush(QColor(pal["chip"]))
            painter.setPen(Qt.NoPen)
            painter.drawRoundedRect(qr, 6, 6)
            painter.setPen(QColor(pal["muted"]))
            painter.setFont(self.f_small)
            q = m.quote
            qname = self.name_of(q) if isinstance(q, Message) else str(getattr(q, "name", "") or "")
            qtext = str(getattr(q, "text", "") or "")[:90]
            painter.drawText(
                qr.adjusted(8, 2, -6, -2),
                int(Qt.TextWordWrap) | int(Qt.AlignLeft) | int(Qt.AlignVCenter),
                "┃ %s：%s" % (qname, qtext),
            )
            y += quote_h + 6

        painter.setFont(self.f_body)
        painter.setPen(QColor(pal["mine_tx"] if mine else pal["other_tx"]))
        tr = QRectF(bx + self.PAD_X, y, bw - self.PAD_X * 2, text_h)
        painter.drawText(tr, int(Qt.TextWordWrap) | int(Qt.AlignLeft) | int(Qt.AlignTop), m.text)
        painter.restore()

    def editorEvent(self, event, model, option, index) -> bool:  # noqa: D401
        return False
