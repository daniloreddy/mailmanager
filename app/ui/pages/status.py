import os
from datetime import datetime

from nicegui import app as nicegui_app
from nicegui import ui
from redberry_webkit.timezone_utils import resolve_timezone

from ...config import config
from ..components import metric_card
from ..theme import base_layout

_TZ = resolve_timezone(os.environ.get("TZ", "UTC"))


def _fmt(epoch: float | None) -> str:
    if not epoch:
        return "—"
    return datetime.fromtimestamp(epoch, tz=_TZ).strftime("%m/%d/%Y %H:%M:%S")


@ui.page("/")
async def status_page() -> None:
    scheduler = nicegui_app.state.scheduler
    metrics = nicegui_app.state.metrics

    @ui.refreshable
    def content() -> None:
        s = scheduler.get_status()

        with ui.grid(columns=4).classes("full-width q-mb-md"):
            metric_card(
                "Stato",
                "● In esecuzione" if s.is_running else "○ Inattivo",
                "positive" if s.is_running else "grey",
            )
            metric_card("Esecuzioni totali", str(s.run_count))
            metric_card("Ultima esecuzione", _fmt(s.last_run_at), "grey")
            metric_card("Prossima esecuzione", _fmt(s.next_run_at), "grey")

        if s.last_error:
            with ui.card().classes("q-pa-sm q-mb-md full-width"):
                ui.label(f"Ultimo errore: {s.last_error}").classes("text-negative text-body2")

        async def run_now() -> None:
            if scheduler.trigger_run_now():
                ui.notify("Esecuzione avviata", type="positive")
                content.refresh()
            else:
                ui.notify("Già in esecuzione", type="warning")

        ui.button("▶ Esegui ora", on_click=run_now).props(
            "color=primary" + (" disabled" if s.is_running else "")
        )

    @ui.refreshable
    async def history_table() -> None:
        history = await metrics.get_history(limit=50)

        with ui.card().classes("full-width q-mt-md"):
            ui.label("Storico run").classes("text-h6 q-mb-sm")
            if not history:
                ui.label("Nessun run ancora eseguito").classes("text-grey-6")
                return
            rows = [
                {
                    "id": str(index),
                    "timestamp": _fmt(record.timestamp),
                    "account": (record.extra or {}).get("account", "—"),
                    "status": record.status,
                    "duration_s": f"{record.duration_s:.2f}",
                    "error_message": record.error_message or "",
                }
                for index, record in enumerate(history)
            ]
            tbl = ui.table(
                columns=[
                    {"name": "timestamp", "label": "Quando", "field": "timestamp"},
                    {"name": "account", "label": "Account", "field": "account"},
                    {"name": "status", "label": "Stato", "field": "status"},
                    {"name": "duration_s", "label": "Durata (s)", "field": "duration_s"},
                    {"name": "error_message", "label": "Errore", "field": "error_message"},
                ],
                rows=rows,
                row_key="id",
            ).classes("full-width")
            tbl.add_slot(
                "body-cell-status",
                """
                <q-td :props="props">
                  <q-badge
                    :color="props.value === 'ok' ? 'positive' : 'negative'"
                    :label="props.value"
                  />
                </q-td>
                """,
            )

    with base_layout("Stato"):
        with ui.column().classes("full-width").style("padding:1.25rem;"):
            content()
            await history_table()
            refresh_lbl = (
                ui.label("")
                .classes("text-caption text-grey-6")
                .style("text-align:right; width:100%")
            )

            refresh_enabled = config.get_bool("REFRESH_ENABLED")
            interval = config.get_int("REFRESH_INTERVAL", 30)

            if refresh_enabled and interval > 0:

                def _update_lbl() -> None:
                    now = datetime.now(_TZ).strftime("%H:%M:%S")
                    refresh_lbl.set_text(f"Aggiornato: {now} · auto-refresh {interval}s")

                def _refresh() -> None:
                    content.refresh()
                    history_table.refresh()
                    _update_lbl()

                _update_lbl()
                ui.timer(float(interval), _refresh)
            else:
                refresh_lbl.set_text("auto-refresh disabilitato")
