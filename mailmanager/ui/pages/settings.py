import logging

from nicegui import app as nicegui_app
from nicegui import ui

from ...models import (
    LoggingConfig,
    LoggingLevel,
    SchedulerConfig,
    SpamAssassinConfig,
    UiConfig,
)
from ..theme import base_layout

_LOG_LEVELS = [lv.value for lv in LoggingLevel]
_root_logger = logging.getLogger()


@ui.page("/settings")
async def settings_page() -> None:
    db = nicegui_app.state.db

    spam = db.load_spam_config()
    sched = db.load_scheduler_config()
    log = db.load_logging_config()
    ui_cfg = db.load_ui_config()

    with base_layout("Settings"):
        with ui.column().classes("full-width").style("padding:1.25rem; gap:1rem;"):
            # ── SpamAssassin ───────────────────────────────────────────
            with ui.card().classes("full-width q-pa-md"):
                ui.label("SpamAssassin").classes("text-h6 q-mb-md")

                spam_enabled = ui.checkbox("Enabled", value=spam.enabled)
                with (
                    ui.grid(columns=2).classes("full-width q-mt-sm").style("gap:12px;")
                ):
                    spam_host = ui.input("Host", value=spam.host)
                    spam_port = ui.number("Port", value=spam.port, min=1, max=65535)
                    spam_user = ui.input("User (optional)", value=spam.user or "")
                with ui.grid(columns=2).classes("full-width").style("gap:12px;"):
                    spam_conn_ms = ui.number(
                        "Connect Timeout (ms)", value=spam.connectTimeoutMillis, min=0
                    )
                    spam_read_ms = ui.number(
                        "Read Timeout (ms)", value=spam.readTimeoutMillis, min=0
                    )

                async def save_spam() -> None:
                    try:
                        cfg = SpamAssassinConfig(
                            enabled=bool(spam_enabled.value),
                            host=str(spam_host.value or "127.0.0.1"),
                            port=int(spam_port.value or 783),
                            user=str(spam_user.value) or None,
                            connectTimeoutMillis=int(spam_conn_ms.value or 3000),
                            readTimeoutMillis=int(spam_read_ms.value or 5000),
                        )
                        db.save_spam_config(cfg)
                        ui.notify("Saved", type="positive")
                    except Exception as exc:
                        ui.notify(f"Error: {exc}", type="negative")

                ui.button("Save", on_click=save_spam).props(
                    "color=primary size=sm"
                ).classes("q-mt-sm")

            # ── Scheduler ──────────────────────────────────────────────
            with ui.card().classes("full-width q-pa-md"):
                ui.label("Scheduler").classes("text-h6 q-mb-md")

                sched_enabled = ui.checkbox("Enabled", value=sched.enabled)
                sched_interval = ui.number(
                    "Interval (seconds)", value=sched.intervalSeconds, min=1
                ).classes("q-mt-sm")

                async def save_sched() -> None:
                    try:
                        cfg = SchedulerConfig(
                            enabled=bool(sched_enabled.value),
                            intervalSeconds=int(sched_interval.value or 300),
                        )
                        db.save_scheduler_config(cfg)
                        ui.notify("Saved", type="positive")
                    except Exception as exc:
                        ui.notify(f"Error: {exc}", type="negative")

                ui.button("Save", on_click=save_sched).props(
                    "color=primary size=sm"
                ).classes("q-mt-sm")

            # ── Logging ────────────────────────────────────────────────
            with ui.card().classes("full-width q-pa-md"):
                ui.label("Logging").classes("text-h6 q-mb-md")

                log_level = ui.select(_LOG_LEVELS, value=log.level.value, label="Level")

                async def save_log() -> None:
                    try:
                        cfg = LoggingConfig(level=LoggingLevel(log_level.value))
                        db.save_logging_config(cfg)
                        _root_logger.setLevel(cfg.level.value)
                        ui.notify("Saved", type="positive")
                    except Exception as exc:
                        ui.notify(f"Error: {exc}", type="negative")

                ui.button("Save", on_click=save_log).props(
                    "color=primary size=sm"
                ).classes("q-mt-sm")

            # ── UI ─────────────────────────────────────────────────────
            with ui.card().classes("full-width q-pa-md"):
                ui.label("UI").classes("text-h6 q-mb-md")

                ui_refresh_enabled = ui.checkbox(
                    "Auto-refresh dashboards", value=ui_cfg.autoRefreshEnabled
                )
                ui_refresh_secs = ui.number(
                    "Refresh interval (seconds)", value=ui_cfg.autoRefreshSeconds, min=1
                ).classes("q-mt-sm")

                async def save_ui() -> None:
                    try:
                        cfg = UiConfig(
                            autoRefreshEnabled=bool(ui_refresh_enabled.value),
                            autoRefreshSeconds=int(ui_refresh_secs.value or 30),
                        )
                        db.save_ui_config(cfg)
                        ui.notify("Saved", type="positive")
                    except Exception as exc:
                        ui.notify(f"Error: {exc}", type="negative")

                ui.button("Save", on_click=save_ui).props(
                    "color=primary size=sm"
                ).classes("q-mt-sm")
