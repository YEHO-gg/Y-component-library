"""ycomponent — small, dependency-free (except PySide6) Qt widgets.

Currently ships one: :class:`ycomponent.chatview.ChatView`.
"""

from .chatview import ChatView, ChatDelegate, ChatModel, Message, THEMES

__all__ = ["ChatView", "ChatDelegate", "ChatModel", "Message", "THEMES"]
__version__ = "0.1.0"
