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

        with ui.grid(columns=4).classes("full-width q-mb-md"):
            metric_card(
                "Status",
                "● Running" if s.is_running else "○ Idle",
                "positive" if s.is_running else "grey",
            )
            metric_card("Total Runs", str(s.run_count))
            metric_card("Last Run", _fmt(s.last_run_at), "grey")
            metric_card("Next Run", _fmt(s.next_run_at), "grey")

        if s.last_error:
            with ui.card().classes("q-pa-sm q-mb-md full-width"):
                ui.label(f"Last error: {s.last_error}").classes(
                    "text-negative text-body2"
                )

        async def run_now() -> None:
            if scheduler.trigger_run_now():
                ui.notify("Run triggered", type="positive")
                content.refresh()
            else:
                ui.notify("Already running", type="warning")

        ui.button("▶ Run Now", on_click=run_now).props(
            "color=primary" + (" disabled" if s.is_running else "")
        )

    ui_cfg = nicegui_app.state.db.load_ui_config()

    with base_layout("Status"):
        with ui.column().classes("full-width").style("padding:1.25rem;"):
            content()
            refresh_lbl = (
                ui.label("")
                .classes("text-caption text-grey-6")
                .style("text-align:right; width:100%")
            )

            if ui_cfg.autoRefreshEnabled and ui_cfg.autoRefreshSeconds > 0:
                interval = ui_cfg.autoRefreshSeconds

                def _update_lbl() -> None:
                    now = datetime.now().strftime("%H:%M:%S")
                    refresh_lbl.set_text(
                        f"Aggiornato: {now} · auto-refresh {interval}s"
                    )

                def _refresh() -> None:
                    content.refresh()
                    _update_lbl()

                _update_lbl()
                ui.timer(float(interval), _refresh)
            else:
                refresh_lbl.set_text("auto-refresh disabilitato")
