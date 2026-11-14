"""Render the demo conversation to a PNG (works headless).

    QT_QPA_PLATFORM=offscreen python examples/shot.py docs/preview.png
"""

from __future__ import annotations

import os
import sys

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from PySide6.QtGui import QFontDatabase  # noqa: E402
from PySide6.QtWidgets import QApplication  # noqa: E402

from demo import demo_messages  # noqa: E402
from ycomponent.chatview import ChatView  # noqa: E402

# The offscreen platform ships no fonts (Qt dropped bundled fonts), so text would
# render as tofu boxes. Load a system CJK font explicitly for the screenshot.
FONT_CANDIDATES = [
    r"C:\Windows\Fonts\msyh.ttc",
    r"C:\Windows\Fonts\msyhbd.ttc",
    r"C:\Windows\Fonts\simhei.ttf",
    "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
]


def main() -> int:
    out = sys.argv[1] if len(sys.argv) > 1 else "docs/preview.png"   # 相对 packages/chatview
    app = QApplication(sys.argv[:1])
    for path in FONT_CANDIDATES:
        if os.path.exists(path):
            fid = QFontDatabase.addApplicationFont(path)
            if fid != -1:
                print(f"loaded font: {path}")
                break
    view = ChatView(my_uid=1, name_of=lambda m: m.name, theme="dark")
    view.resize(640, 700)
    view.set_messages(demo_messages())
    view.show()
    app.processEvents()
    view.scroll_to_bottom(force=True)
    app.processEvents()
    pix = view.grab()
    os.makedirs(os.path.dirname(out) or ".", exist_ok=True)
    pix.save(out)
    print(f"wrote {out} ({pix.width()}x{pix.height()})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
