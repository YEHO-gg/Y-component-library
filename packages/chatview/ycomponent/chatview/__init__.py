"""Chat transcript widget: QListView + delegate, built for long lists."""

from .delegate import ChatDelegate, default_name_of, fmt_separator, text_height
from .model import ROLE_MSG, ChatModel, Message
from .theme import DARK, LIGHT, THEMES
from .view import BOTTOM_EPSILON, SEPARATOR_GAP, ChatView, JumpPill

__all__ = [
    "ChatView",
    "ChatModel",
    "ChatDelegate",
    "Message",
    "JumpPill",
    "ROLE_MSG",
    "THEMES",
    "LIGHT",
    "DARK",
    "fmt_separator",
    "default_name_of",
    "text_height",
    "SEPARATOR_GAP",
    "BOTTOM_EPSILON",
]
