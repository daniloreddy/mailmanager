from nicegui import ui


def metric_card(label: str, value: str, color: str = "primary") -> None:
    with ui.card().classes("q-pa-md"):
        ui.label(label).classes("text-caption text-grey-6 text-uppercase")
        ui.label(value).classes(f"text-h5 text-weight-bold text-{color}")


def status_badge(text: str, status: str) -> None:
    color_map = {"ok": "positive", "warn": "warning", "err": "negative"}
    color = color_map.get(status, "grey")
    ui.badge(text).props(f"color={color}")
