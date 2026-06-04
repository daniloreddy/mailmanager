from textual.app import App, ComposeResult
from textual.widgets import (
    Header,
    Footer,
    TabbedContent,
    TabPane,
    DataTable,
    Button,
    Static,
    Input,
    Select,
    Checkbox,
)
from textual.containers import Container, Horizontal
from textual.screen import ModalScreen
from textual.binding import Binding
from textual import work
from typing import Optional, List, Dict, Any
from .db import Db
from .models import (
    ImapConfig,
    Rule,
    SpamAssassinConfig,
    ActionType,
    ConditionOperator,
    ConditionSubject,
)
from .processing import ProcessingService


class ConfigModal(ModalScreen):
    def __init__(self, db: Db, item_type: str, item=None):
        super().__init__()
        self.db = db
        self.item_type = item_type
        self.item = item
        self.inputs: Dict[str, Any] = {}

    def compose(self) -> ComposeResult:
        if self.item_type == "imap":
            yield self._imap_form()
        elif self.item_type == "rule":
            yield self._rule_form()
        else:
            yield self._spam_form()

    def _imap_form(self):
        cfg = self.item or ImapConfig()
        return Container(
            Static("IMAP Config"),
            Input(cfg.name, placeholder="Name", id="name"),
            Input(cfg.host, placeholder="Host", id="host"),
            Input(str(cfg.port), placeholder="Port", id="port"),
            Input(cfg.username, placeholder="User", id="username"),
            Input(cfg.password, placeholder="Pass", id="password", password=True),
            Checkbox(
                label="Use SpamAssassin", value=cfg.useSpamAssassin, id="use_spam"
            ),
            Horizontal(
                Button("Save", id="save"),
                Button("Cancel", id="cancel"),
                classes="buttons",
            ),
            id="form",
        )

    def _rule_form(self):
        r = self.item or Rule(
            imapConfigName="",
            actionType=ActionType.MOVE,
            conditionOperator=ConditionOperator.CONTAINS,
            conditionSubject=ConditionSubject.SUBJECT,
        )
        imaps = self.db.load_imaps()
        imap_options = [(i.name, i.name) for i in imaps]
        return Container(
            Static("Rule"),
            Select(
                imap_options,
                value=r.imapConfigName
                if r.imapConfigName
                else (imap_options[0][0] if imap_options else None),
                id="imap_name",
            ),
            Select(
                [(a.value, a.value) for a in ActionType],
                value=r.actionType.value,
                id="action",
            ),
            Select(
                [(c.value, c.value) for c in ConditionSubject],
                value=r.conditionSubject.value,
                id="subject",
            ),
            Select(
                [(o.value, o.value) for o in ConditionOperator],
                value=r.conditionOperator.value,
                id="op",
            ),
            Input(r.conditionValue, placeholder="Value", id="value"),
            Input(r.destValue, placeholder="Dest", id="dest"),
            Horizontal(
                Button("Save", id="save"),
                Button("Cancel", id="cancel"),
                classes="buttons",
            ),
            id="form",
        )

    def _spam_form(self):
        s = self.item or SpamAssassinConfig()
        return Container(
            Static("SpamAssassin"),
            Checkbox(label="Enabled", value=s.enabled, id="enabled"),
            Input(s.host, placeholder="Host", id="host"),
            Input(str(s.port), placeholder="Port", id="port"),
            Horizontal(
                Button("Save", id="save"),
                Button("Cancel", id="cancel"),
                classes="buttons",
            ),
            id="form",
        )

    def on_button_pressed(self, event):
        if event.button.id == "cancel":
            self.dismiss()
            return
        if event.button.id == "save":
            try:
                if self.item_type == "imap":
                    data = {
                        k: self.query_one(f"#{k}").value
                        for k in ["name", "host", "port", "username", "password"]
                    }
                    data["useSpamAssassin"] = self.query_one("#use_spam").value
                    cfg = ImapConfig(**data)
                    self.db.save_imaps([cfg])
                elif self.item_type == "rule":
                    data = {
                        "imapConfigName": self.query_one("#imap_name").value,
                        "conditionValue": self.query_one("#value").value,
                        "destValue": self.query_one("#dest").value,
                        "actionType": self.query_one("#action").value,
                        "conditionSubject": self.query_one("#subject").value,
                        "conditionOperator": self.query_one("#op").value,
                    }
                    rule = Rule(**data)
                    if self.item:
                        rule.id = self.item.id
                    self.db.save_rule(rule)
                else:
                    port_val = self.query_one("#port").value
                    data = {
                        "enabled": self.query_one("#enabled").value,
                        "host": self.query_one("#host").value,
                        "port": int(port_val) if port_val.isdigit() else 783,
                    }
                    self.db.save_spam_config(SpamAssassinConfig(**data))
                self.dismiss(True)
            except Exception as e:
                self.app.notify(f"Error saving: {e}", severity="error")


class MailManagerApp(App):
    CSS = """
    Horizontal {
        height: auto;
        margin-top: 1;
    }
    Button {
        margin-right: 1;
    }
    #form {
        padding: 1;
        border: thick $primary;
        width: 60;
        height: auto;
        background: $surface;
    }
    .buttons {
        height: auto;
        margin-top: 1;
    }
    """
    BINDINGS = [
        Binding("d", "toggle_dark", "Dark"),
        Binding("q", "quit", "Quit"),
        Binding("n", "new_item", "New"),
        Binding("r", "run", "Run"),
        Binding("enter", "edit_selected", "Edit"),
    ]

    def __init__(self, db: Optional[Db] = None):
        super().__init__()
        self.db = db or Db()
        self.imaps: List[ImapConfig] = []
        self.rules: List[Rule] = []

    def compose(self) -> ComposeResult:
        yield Header()
        with TabbedContent():
            with TabPane("IMAP"):
                yield DataTable(id="imap_table", cursor_type="row")
                yield Horizontal(
                    Button("Add", id="add_imap"),
                    Button("Edit", id="edit_imap"),
                    Button("Delete", id="del_imap"),
                )
            with TabPane("Rules"):
                yield DataTable(id="rule_table", cursor_type="row")
                yield Horizontal(
                    Button("Add", id="add_rule"),
                    Button("Edit", id="edit_rule"),
                    Button("Delete", id="del_rule"),
                )
            with TabPane("Spam"):
                yield DataTable(id="spam_table", cursor_type="row")
                yield Horizontal(Button("Edit", id="edit_spam"))
        yield Footer()

    def on_mount(self):
        self.refresh_all()

    def refresh_all(self):
        self._load_imap()
        self._load_rules()
        self._load_spam()

    def _load_imap(self):
        table = self.query_one("#imap_table", DataTable)
        table.clear(columns=True)
        table.add_columns("Name", "Host", "User", "Spam")
        self.imaps = self.db.load_imaps()
        for i in self.imaps:
            table.add_row(
                i.name,
                i.host,
                i.username,
                "Yes" if i.useSpamAssassin else "No",
                key=i.name,
            )

    def _load_rules(self):
        table = self.query_one("#rule_table", DataTable)
        table.clear(columns=True)
        # Add a hidden ID column to uniquely identify rules
        table.add_columns("ID", "IMAP", "Action", "Subject", "Op", "Value")
        # Textual DataTable doesn't easily support hidden columns in older versions.
        # Instead, we will store the id in the row key.
        table.clear(columns=True)
        table.add_columns("IMAP", "Action", "Subject", "Op", "Value")

        self.rules = self.db.load_rules()
        for r in self.rules:
            table.add_row(
                r.imapConfigName,
                r.actionType.value,
                r.conditionSubject.value,
                r.conditionOperator.value,
                r.conditionValue,
                key=str(r.id),
            )

    def _load_spam(self):
        table = self.query_one("#spam_table", DataTable)
        table.clear(columns=True)
        table.add_columns("Enabled", "Host", "Port")
        s = self.db.load_spam_config()
        table.add_row(str(s.enabled), s.host, str(s.port))

    def on_button_pressed(self, event):
        if event.button.id == "add_imap":
            self.push_screen(
                ConfigModal(self.db, "imap"), callback=lambda _: self.refresh_all()
            )
        elif event.button.id == "edit_imap":
            self._edit_imap()
        elif event.button.id == "del_imap":
            self._del_imap()
        elif event.button.id == "add_rule":
            self.push_screen(
                ConfigModal(self.db, "rule"), callback=lambda _: self.refresh_all()
            )
        elif event.button.id == "edit_rule":
            self._edit_rule()
        elif event.button.id == "del_rule":
            self._del_rule()
        elif event.button.id == "edit_spam":
            self.push_screen(
                ConfigModal(self.db, "spam", self.db.load_spam_config()),
                callback=lambda _: self.refresh_all(),
            )

    def action_new_item(self):
        self.push_screen(
            ConfigModal(self.db, "imap"), callback=lambda _: self.refresh_all()
        )

    @work(thread=True)
    def action_run(self):
        self.notify("Starting processing...")
        spam_config = self.db.load_spam_config()
        imaps = self.db.load_imaps()
        rules = self.db.load_rules()

        if not imaps:
            self.notify("No IMAP configurations found.", severity="warning")
            return

        service = ProcessingService(self.db, rules, spam_config)

        for imap in imaps:
            self.notify(f"Processing account: {imap.name}")
            service.process_account(imap)

        self.notify("Processing complete.")

    def _edit_imap(self):
        table = self.query_one("#imap_table", DataTable)
        if table.cursor_row is not None and table.row_count > 0:
            row_key = table.coordinate_to_cell_key((table.cursor_row, 0)).row_key
            name = row_key.value
            item = next((i for i in self.imaps if i.name == name), None)
            if item:
                self.push_screen(
                    ConfigModal(self.db, "imap", item),
                    callback=lambda _: self.refresh_all(),
                )

    def _del_imap(self):
        table = self.query_one("#imap_table", DataTable)
        if table.cursor_row is not None and table.row_count > 0:
            row_key = table.coordinate_to_cell_key((table.cursor_row, 0)).row_key
            name = row_key.value
            self.db.delete_imap(name)
            self.refresh_all()

    def _edit_rule(self):
        table = self.query_one("#rule_table", DataTable)
        if table.cursor_row is not None and table.row_count > 0:
            row_key = table.coordinate_to_cell_key((table.cursor_row, 0)).row_key
            rule_id_str = row_key.value
            if rule_id_str and rule_id_str != "None":
                rule_id = int(rule_id_str)
                item = next((r for r in self.rules if r.id == rule_id), None)
                if item:
                    self.push_screen(
                        ConfigModal(self.db, "rule", item),
                        callback=lambda _: self.refresh_all(),
                    )

    def _del_rule(self):
        table = self.query_one("#rule_table", DataTable)
        if table.cursor_row is not None and table.row_count > 0:
            row_key = table.coordinate_to_cell_key((table.cursor_row, 0)).row_key
            rule_id_str = row_key.value
            if rule_id_str and rule_id_str != "None":
                rule_id = int(rule_id_str)
                self.db.delete_rule(rule_id)
                self.refresh_all()

    def on_data_table_row_selected(self, event: DataTable.RowSelected):
        if event.data_table.id == "imap_table":
            self._edit_imap()
        elif event.data_table.id == "rule_table":
            self._edit_rule()
        elif event.data_table.id == "spam_table":
            self.push_screen(
                ConfigModal(self.db, "spam", self.db.load_spam_config()),
                callback=lambda _: self.refresh_all(),
            )

    def action_edit_selected(self):
        focused = self.focused
        if focused and focused.id == "imap_table":
            self._edit_imap()
        elif focused and focused.id == "rule_table":
            self._edit_rule()
        elif focused and focused.id == "spam_table":
            self.push_screen(
                ConfigModal(self.db, "spam", self.db.load_spam_config()),
                callback=lambda _: self.refresh_all(),
            )
