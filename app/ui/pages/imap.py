from typing import Any

from nicegui import app as nicegui_app
from nicegui import ui

from ...models import ImapConfig, SpamAction
from ..theme import base_layout

_SPAM_ACTIONS = [a.value for a in SpamAction]


def _imap_form(cfg: ImapConfig | None = None) -> dict[str, Any]:
    is_edit = cfg is not None
    c = cfg or ImapConfig()
    refs: dict[str, Any] = {}

    def _sep(title: str) -> None:
        ui.label(title).classes(
            "text-caption text-grey-6 text-uppercase q-mt-sm q-mb-xs full-width"
        )
        ui.separator()

    _sep("Connection")
    with ui.grid(columns=2).classes("full-width").style("gap:12px;"):
        refs["name"] = ui.input("Name *", value=c.name).classes("col-span-1")
        if is_edit:
            refs["name"].props("readonly")
        refs["host"] = ui.input("Host *", value=c.host).classes("col-span-1")
        refs["port"] = ui.input("Port", value=c.port).classes("col-span-1")
        refs["inbox_folder"] = ui.input("Inbox Folder", value=c.inbox_folder).classes("col-span-1")
        refs["username"] = ui.input("Username", value=c.username).classes("col-span-1")
        refs["password"] = ui.input(
            "Password", value=c.password, password=True, password_toggle_button=True
        ).classes("col-span-1")
    with ui.row().classes("full-width q-gutter-md"):
        refs["ssl"] = ui.checkbox("SSL", value=c.ssl)
        refs["auth"] = ui.checkbox("Auth", value=c.auth)

    _sep("Spam")
    with ui.grid(columns=2).classes("full-width").style("gap:12px;"):
        refs["spam_action"] = ui.select(
            _SPAM_ACTIONS, value=c.spam_action.value, label="Spam Action"
        )
        refs["spam_folder"] = ui.input("Spam Folder", value=c.spam_folder)
    with ui.row().classes("full-width"):
        refs["use_spam_assassin"] = ui.checkbox("Use SpamAssassin", value=c.use_spam_assassin)

    _sep("Timeouts (ms)")
    with ui.grid(columns=3).classes("full-width").style("gap:12px;"):
        refs["connection_timeout_ms"] = ui.number("Connect", value=c.connection_timeout_ms, min=0)
        refs["read_timeout_ms"] = ui.number("Read", value=c.read_timeout_ms, min=0)
        refs["write_timeout_ms"] = ui.number("Write", value=c.write_timeout_ms, min=0)

    _sep("SMTP (for forwarding)")
    with ui.grid(columns=2).classes("full-width").style("gap:12px;"):
        refs["smtp_host"] = ui.input("Host", value=c.smtp_host)
        refs["smtp_port"] = ui.input("Port", value=c.smtp_port)
        refs["smtp_username"] = ui.input("Username", value=c.smtp_username)
        refs["smtp_password"] = ui.input(
            "Password", value=c.smtp_password, password=True, password_toggle_button=True
        )
    with ui.row().classes("full-width q-gutter-md"):
        refs["smtp_ssl"] = ui.checkbox("SSL", value=c.smtp_ssl)
        refs["smtp_auth"] = ui.checkbox("Auth", value=c.smtp_auth)

    return refs


def _refs_to_imap(refs: dict[str, Any]) -> ImapConfig:
    def _int(v: object) -> int:
        return int(str(v)) if v is not None else 0

    return ImapConfig(
        name=str(refs["name"].value or ""),
        host=str(refs["host"].value or ""),
        port=str(refs["port"].value or "993"),
        inbox_folder=str(refs["inbox_folder"].value or "INBOX"),
        username=str(refs["username"].value or ""),
        password=str(refs["password"].value or ""),
        ssl=bool(refs["ssl"].value),
        auth=bool(refs["auth"].value),
        use_spam_assassin=bool(refs["use_spam_assassin"].value),
        spam_action=SpamAction(refs["spam_action"].value),
        spam_folder=str(refs["spam_folder"].value or "Junk"),
        connection_timeout_ms=_int(refs["connection_timeout_ms"].value),
        read_timeout_ms=_int(refs["read_timeout_ms"].value),
        write_timeout_ms=_int(refs["write_timeout_ms"].value),
        smtp_host=str(refs["smtp_host"].value or ""),
        smtp_port=str(refs["smtp_port"].value or "587"),
        smtp_username=str(refs["smtp_username"].value or ""),
        smtp_password=str(refs["smtp_password"].value or ""),
        smtp_ssl=bool(refs["smtp_ssl"].value),
        smtp_auth=bool(refs["smtp_auth"].value),
    )


@ui.page("/imap")
async def imap_page() -> None:
    db = nicegui_app.state.db

    @ui.refreshable
    def imap_table() -> None:
        configs = db.load_imaps()

        with ui.card().classes("full-width"):
            with ui.row().classes("justify-between items-center q-mb-md"):
                ui.label("IMAP Configurations").classes("text-h6")
                ui.button("+ Add", on_click=lambda: open_dialog()).props("color=primary size=sm")

            if not configs:
                ui.label("No configurations").classes("text-grey-6 text-center q-pa-xl full-width")
                return

            with ui.row().classes(
                "q-px-sm q-py-xs text-grey-6 full-width text-caption "
                "text-uppercase text-weight-bold"
            ):
                ui.label("Name").style("width:140px;")
                ui.label("Host").style("width:200px;")
                ui.label("Username").style("flex:1;")
                ui.label("SSL").style("width:55px;")
                ui.label("Spam").style("width:55px;")
                ui.label("").style("width:100px;")
            ui.separator()

            for c in configs:
                with ui.row().classes("items-center q-px-sm q-py-xs full-width"):
                    ui.label(c.name).classes("text-weight-bold").style("width:140px;")
                    ui.label(f"{c.host}:{c.port}").classes("text-grey-6").style("width:200px;")
                    ui.label(c.username).classes("text-grey-6").style("flex:1;")
                    _badge("SSL" if c.ssl else "Plain", "ok" if c.ssl else "off", "55px")
                    _badge(
                        "On" if c.use_spam_assassin else "Off",
                        "ok" if c.use_spam_assassin else "off",
                        "55px",
                    )
                    with ui.row().style("width:100px;").classes("q-gutter-xs"):
                        ui.button("Edit", on_click=lambda c=c: open_dialog(c)).props("flat size=sm")
                        ui.button("Del", on_click=lambda c=c: confirm_delete(c.name)).props(
                            "flat size=sm color=negative"
                        )
                ui.separator()

    def _badge(text: str, kind: str, width: str) -> None:
        color = "positive" if kind == "ok" else "grey"
        with ui.element("div").style(f"width:{width}; display:flex; align-items:center;"):
            ui.badge(text).props(f"color={color}")

    async def open_dialog(cfg: ImapConfig | None = None) -> None:
        is_edit = cfg is not None
        title = "Edit IMAP Config" if is_edit else "Add IMAP Config"

        with (
            ui.dialog() as dialog,
            ui.card().style("width:620px; max-height:85vh; overflow-y:auto;"),
        ):
            ui.label(title).classes("text-h6 q-mb-sm")
            refs = _imap_form(cfg)

            async def save() -> None:
                try:
                    new_cfg = _refs_to_imap(refs)
                    if not new_cfg.name:
                        ui.notify("Name is required", type="negative")
                        return
                    db.save_imaps([new_cfg])
                    ui.notify("Saved", type="positive")
                    dialog.close()
                    imap_table.refresh()
                except Exception as exc:
                    ui.notify(f"Error: {exc}", type="negative")

            with ui.row().classes("justify-end q-mt-md q-gutter-xs"):
                ui.button("Cancel", on_click=dialog.close).props("flat").classes("text-grey-6")
                ui.button("Save", on_click=save).props("color=primary")

        dialog.open()

    async def confirm_delete(name: str) -> None:
        with ui.dialog() as dlg, ui.card():
            ui.label(f'Delete "{name}"?').classes("text-h6 q-mb-md")
            with ui.row().classes("justify-end q-gutter-xs"):
                ui.button("Cancel", on_click=dlg.close).props("flat").classes("text-grey-6")

                async def do_delete() -> None:
                    db.delete_imap(name)
                    dlg.close()
                    ui.notify(f'Deleted "{name}"', type="positive")
                    imap_table.refresh()

                ui.button("Delete", on_click=do_delete).props("color=negative")
        dlg.open()

    with base_layout("IMAP"):
        with ui.column().classes("full-width").style("padding:1.25rem;"):
            imap_table()
