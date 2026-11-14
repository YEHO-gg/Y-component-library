"""Light / dark palettes for the chat view.

Every colour used by the delegate comes from one of these dicts, so a theme
switch is just "swap the dict and repaint" — no widget needs to know about it.
"""

from __future__ import annotations

LIGHT: dict[str, str] = {
    "bg": "#F2F5F7",
    "surface": "#FFFFFF",
    "surface2": "#F7FAFB",
    "line": "#E1E8EC",
    "text": "#1E2A32",
    "muted": "#6B7C86",
    "accent": "#2C7A9E",
    "accent_tx": "#FFFFFF",
    "accent_soft": "#E3F0F6",
    "ok": "#2E7D5B",
    "danger": "#C0392B",
    "warn": "#B58A3C",
    "mine": "#C9EBD5",
    "mine_tx": "#15321F",
    "other": "#FFFFFF",
    "other_tx": "#1E2A32",
    "chip": "#EAEFF2",
    "sel": "#D6E9F2",
}

DARK: dict[str, str] = {
    "bg": "#12181C",
    "surface": "#1A2228",
    "surface2": "#202A31",
    "line": "#2C3841",
    "text": "#E4EDF2",
    "muted": "#8FA3AD",
    "accent": "#5BB6DA",
    "accent_tx": "#0B1519",
    "accent_soft": "#22343D",
    "ok": "#4FB183",
    "danger": "#E06C5A",
    "warn": "#D9B26A",
    "mine": "#2C5B45",
    "mine_tx": "#DDF3E6",
    "other": "#243039",
    "other_tx": "#E4EDF2",
    "chip": "#222E36",
    "sel": "#2B3B45",
}

THEMES: dict[str, dict[str, str]] = {"light": LIGHT, "dark": DARK}
