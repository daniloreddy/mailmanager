import logging

from nicegui import ui

from ...config import config
from ...models import LoggingLevel
from ..theme import base_layout

_LOG_LEVELS = [lv.value for lv in LoggingLevel]
_root_logger = logging.getLogger()


@ui.page("/settings")
async def settings_page() -> None:
    with base_layout("Impostazioni"):
        with ui.column().classes("full-width").style("padding:1.25rem; gap:1rem;"):
            # ── SpamAssassin ───────────────────────────────────────────
            with ui.card().classes("full-width q-pa-md"):
                ui.label("SpamAssassin").classes("text-h6 q-mb-md")

                spam_enabled = ui.checkbox("Abilitato", value=config.get_bool("SPAM_ENABLED"))
                with ui.grid(columns=2).classes("full-width q-mt-sm").style("gap:12px;"):
                    spam_host = ui.input("Host", value=config.get("SPAM_HOST", "127.0.0.1"))
                    spam_port = ui.number(
                        "Porta", value=config.get_int("SPAM_PORT", 783), min=1, max=65535
                    )
                    spam_user = ui.input("Utente (opzionale)", value=config.get("SPAM_USER", ""))
                with ui.grid(columns=2).classes("full-width").style("gap:12px;"):
                    spam_conn_ms = ui.number(
                        "Timeout connessione (ms)",
                        value=config.get_int("SPAM_CONNECT_TIMEOUT_MS", 3000),
                        min=0,
                    )
                    spam_read_ms = ui.number(
                        "Timeout lettura (ms)",
                        value=config.get_int("SPAM_READ_TIMEOUT_MS", 5000),
                        min=0,
                    )

                async def save_spam() -> None:
                    try:
                        config.update_many(
                            {
                                "SPAM_ENABLED": "true" if spam_enabled.value else "false",
                                "SPAM_HOST": str(spam_host.value or "127.0.0.1"),
                                "SPAM_PORT": str(int(spam_port.value or 783)),
                                "SPAM_USER": str(spam_user.value or ""),
                                "SPAM_CONNECT_TIMEOUT_MS": str(int(spam_conn_ms.value or 3000)),
                                "SPAM_READ_TIMEOUT_MS": str(int(spam_read_ms.value or 5000)),
                            }
                        )
                        ui.notify("Salvato", type="positive")
                    except Exception as exc:
                        ui.notify(f"Errore: {exc}", type="negative")

                ui.button("Salva", on_click=save_spam).props("color=primary size=sm").classes(
                    "q-mt-sm"
                )

            # ── Scheduler ──────────────────────────────────────────────
            with ui.card().classes("full-width q-pa-md"):
                ui.label("Scheduler").classes("text-h6 q-mb-md")

                sched_enabled = ui.checkbox("Abilitato", value=config.get_bool("SCHEDULER_ENABLED"))
                sched_interval = ui.number(
                    "Intervallo (secondi)",
                    value=config.get_int("SCHEDULER_INTERVAL_SECONDS", 300),
                    min=1,
                ).classes("q-mt-sm")

                async def save_sched() -> None:
                    try:
                        config.update_many(
                            {
                                "SCHEDULER_ENABLED": "true" if sched_enabled.value else "false",
                                "SCHEDULER_INTERVAL_SECONDS": str(int(sched_interval.value or 300)),
                            }
                        )
                        ui.notify("Salvato", type="positive")
                    except Exception as exc:
                        ui.notify(f"Errore: {exc}", type="negative")

                ui.button("Salva", on_click=save_sched).props("color=primary size=sm").classes(
                    "q-mt-sm"
                )

            # ── Logging ────────────────────────────────────────────────
            with ui.card().classes("full-width q-pa-md"):
                ui.label("Log").classes("text-h6 q-mb-md")

                log_level = ui.select(
                    _LOG_LEVELS, value=config.get("LOG_LEVEL", "INFO"), label="Livello"
                )

                async def save_log() -> None:
                    try:
                        level = LoggingLevel(log_level.value)
                        config.update_many({"LOG_LEVEL": level.value})
                        _root_logger.setLevel(level.value)
                        ui.notify("Salvato", type="positive")
                    except Exception as exc:
                        ui.notify(f"Errore: {exc}", type="negative")

                ui.button("Salva", on_click=save_log).props("color=primary size=sm").classes(
                    "q-mt-sm"
                )

            # ── Interfaccia ────────────────────────────────────────────
            with ui.card().classes("full-width q-pa-md"):
                with ui.row().classes("items-center q-mb-md q-gutter-sm"):
                    ui.label("Interfaccia").classes("text-h6")
                    ui.badge("hot-reload").props("color=positive")

                ui_refresh_enabled = ui.checkbox(
                    "Auto-refresh dashboard", value=config.get_bool("REFRESH_ENABLED")
                )
                ui_refresh_secs = ui.number(
                    "Intervallo di refresh (secondi)",
                    value=config.get_int("REFRESH_INTERVAL", 30),
                    min=1,
                ).classes("q-mt-sm")

                async def save_ui() -> None:
                    try:
                        config.update_many(
                            {
                                "REFRESH_ENABLED": "true" if ui_refresh_enabled.value else "false",
                                "REFRESH_INTERVAL": str(int(ui_refresh_secs.value or 30)),
                            }
                        )
                        ui.notify("Salvato", type="positive")
                    except Exception as exc:
                        ui.notify(f"Errore: {exc}", type="negative")

                ui.button("Salva", on_click=save_ui).props("color=primary size=sm").classes(
                    "q-mt-sm"
                )
