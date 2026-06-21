from datetime import datetime
from typing import Optional

from nicegui import app as nicegui_app
from nicegui import ui

from ..components import metric_card
from ..theme import base_layout


def _fmt(epoch: Optional[float]) -> str:
    if not epoch:
        return "—"
    return datetime.fromtimestamp(epoch).strftime("%m/%d/%Y %H:%M:%S")


@ui.page("/")
async def status_page() -> None:
    scheduler = nicegui_app.state.scheduler

    @ui.refreshable
    def content() -> None:
        s = scheduler.get_status()

        with ui.grid(columns=4).classes("w-full q-mb-md"):
            metric_card(
                "Status",
                "● Running" if s.is_running else "○ Idle",
                "text-teal-300" if s.is_running else "text-grey-5",
            )
            metric_card("Total Runs", str(s.run_count))
            metric_card("Last Run", _fmt(s.last_run_at), "text-grey-3")
            metric_card("Next Run", _fmt(s.next_run_at), "text-grey-3")

        if s.last_error:
            ui.label(f"Last error: {s.last_error}").style(
                "background:#3A1B1B; color:#EF9A9A; padding:12px 16px;"
                " border-radius:8px; margin-bottom:12px; font-size:13px; display:block;"
            )

        async def run_now() -> None:
            if scheduler.trigger_run_now():
                ui.notify("Run triggered", type="positive")
                content.refresh()
            else:
                ui.notify("Already running", type="warning")

        ui.button("▶ Run Now", on_click=run_now).props(
            "color=teal" + (" disabled" if s.is_running else "")
        )

    with base_layout("/"):
        content()
        ui.timer(5.0, content.refresh)
