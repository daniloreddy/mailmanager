from __future__ import annotations

from collections.abc import Callable
from contextlib import contextmanager
from typing import Any, Generator

from nicegui import app as ng_app
from nicegui import ui

_APP_NAME: str = "MailManager"
_VERSION: str = "1.0.0"

_NAV_ITEMS: list[tuple[str, str, str]] = [
    ("Status", "monitor", "/"),
    ("IMAP", "email", "/imap"),
    ("Rules", "rule", "/rules"),
    ("Settings", "settings", "/settings"),
]


def _page_setup(section_title: str) -> Any:
    ui.page_title(f"{section_title} — {_APP_NAME}")
    return ui.dark_mode(value=ng_app.storage.user.get("dark_mode", True))


def _header(
    page_title: str,
    nav_items: list[tuple[str, str, str]],
    current: str = "",
    *,
    dark: Any = None,
    extra_actions: Callable[[], None] | None = None,
) -> None:
    with ui.header().classes("bg-primary text-white items-center q-px-md q-gutter-sm"):
        ui.label(page_title).classes("text-h6 text-weight-bold col")

        for label, icon, path in nav_items:
            if label.lower() != current.lower():
                ui.button(icon=icon, on_click=lambda p=path: ui.navigate.to(p)).props(
                    "flat color=white round"
                ).tooltip(label)

        if extra_actions is not None:
            extra_actions()

        if dark is not None:

            def _toggle_dark() -> None:
                dark.toggle()
                ng_app.storage.user["dark_mode"] = dark.value

            ui.button(icon="contrast", on_click=_toggle_dark).props(
                "flat color=white round"
            ).tooltip("Dark / Light")

        ui.label(_APP_NAME).classes("text-body2").style("opacity:0.6")


def _footer(right_content: str = "") -> None:
    with ui.footer().classes("bg-primary text-white q-px-md q-py-xs row items-center"):
        ui.label(_APP_NAME).classes("col text-caption").style("opacity:0.6")
        if right_content:
            ui.label(right_content).classes("text-body2 text-weight-bold")


@contextmanager
def base_layout(page_title: str) -> Generator[None, None, None]:
    dark = _page_setup(page_title)
    _header(page_title, _NAV_ITEMS, current=page_title, dark=dark)
    yield
    _footer(f"v{_VERSION}")
