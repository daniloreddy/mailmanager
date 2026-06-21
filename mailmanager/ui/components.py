from nicegui import ui

_STATUS_STYLE: dict[str, str] = {
    "ok":   "background:#1B3A2F; color:#80CBC4;",
    "warn": "background:#2F2A1B; color:#FFD54F;",
    "err":  "background:#3A1B1B; color:#EF9A9A;",
}


def metric_card(label: str, value: str, color_class: str = "text-blue-200") -> None:
    with ui.card().classes("q-pa-md").style(
        "background:#1E1E1E; border:1px solid #2A2A2A;"
    ):
        ui.label(label).classes("text-xs text-grey-6 uppercase tracking-widest q-mb-xs")
        ui.label(value).classes(f"text-2xl font-bold {color_class}")


def status_badge(text: str, status: str) -> None:
    style = _STATUS_STYLE.get(status, "background:#1E1E1E; color:#616161;")
    ui.label(text).style(
        f"padding:2px 8px; border-radius:4px; font-size:0.65rem; font-weight:600; {style}"
    )
