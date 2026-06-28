from typing import Optional

from nicegui import app as nicegui_app
from nicegui import ui

from ...models import ActionType, ConditionOperator, ConditionSubject, Rule
from ..theme import base_layout

_FIELDS = [s.value for s in ConditionSubject]
_OPERATORS = [o.value for o in ConditionOperator]
_ACTIONS = [a.value for a in ActionType]


@ui.page("/rules")
async def rules_page() -> None:
    db = nicegui_app.state.db

    @ui.refreshable
    def rules_table() -> None:
        rules = db.load_rules()
        configs = db.load_imaps()
        account_names = [c.name for c in configs]

        with ui.card().classes("full-width"):
            with ui.row().classes("justify-between items-center q-mb-md"):
                ui.label("Rules").classes("text-h6")
                ui.button("+ Add", on_click=lambda: open_dialog(account_names)).props(
                    "color=primary size=sm"
                )

            if not rules:
                ui.label("No rules").classes(
                    "text-grey-6 text-center q-pa-xl full-width"
                )
                return

            with ui.row().classes(
                "q-px-sm q-py-xs text-grey-6 full-width text-caption text-uppercase text-weight-bold"
            ):
                ui.label("Account").style("width:130px;")
                ui.label("Field").style("width:90px;")
                ui.label("Operator").style("width:120px;")
                ui.label("Value").style("flex:1;")
                ui.label("Action").style("width:110px;")
                ui.label("Dest").style("width:100px;")
                ui.label("").style("width:100px;")
            ui.separator()

            for r in rules:
                with ui.row().classes("items-center q-px-sm q-py-xs full-width"):
                    ui.label(r.imapConfigName).classes("text-weight-bold").style(
                        "width:130px;"
                    )
                    ui.label(r.conditionSubject.value).classes("text-grey-6").style(
                        "width:90px;"
                    )
                    ui.label(r.conditionOperator.value).classes("text-grey-6").style(
                        "width:120px;"
                    )
                    ui.label(r.conditionValue).style("flex:1;")
                    ui.label(r.actionType.value).classes("text-grey-6").style(
                        "width:110px;"
                    )
                    ui.label(r.destValue or "—").classes("text-grey-6").style(
                        "width:100px;"
                    )
                    with ui.row().style("width:100px;").classes("q-gutter-xs"):
                        ui.button(
                            "Edit",
                            on_click=lambda r=r, names=account_names: open_dialog(
                                names, r
                            ),
                        ).props("flat size=sm")
                        ui.button(
                            "Del",
                            on_click=lambda r=r: confirm_delete(r.id),
                        ).props("flat size=sm color=negative")
                ui.separator()

    async def open_dialog(
        account_names: list[str], rule: Optional[Rule] = None
    ) -> None:
        is_edit = rule is not None
        r = rule or Rule(
            imapConfigName=account_names[0] if account_names else "",
            actionType=ActionType.MOVE,
            conditionOperator=ConditionOperator.CONTAINS,
            conditionSubject=ConditionSubject.FROM,
        )
        title = "Edit Rule" if is_edit else "Add Rule"

        with ui.dialog() as dialog, ui.card().style("width:560px;"):
            ui.label(title).classes("text-h6 q-mb-sm")

            account_sel = ui.select(
                account_names or ["—"],
                value=r.imapConfigName or (account_names[0] if account_names else ""),
                label="Account",
            ).classes("full-width")

            with ui.grid(columns=2).classes("full-width").style("gap:12px;"):
                field_sel = ui.select(
                    _FIELDS, value=r.conditionSubject.value, label="Field"
                )
                op_sel = ui.select(
                    _OPERATORS, value=r.conditionOperator.value, label="Operator"
                )

            value_inp = ui.input("Value", value=r.conditionValue).classes("full-width")

            with ui.grid(columns=2).classes("full-width").style("gap:12px;"):
                action_sel = ui.select(
                    _ACTIONS, value=r.actionType.value, label="Action"
                )
                dest_inp = ui.input("Destination / Label", value=r.destValue)

            case_chk = ui.checkbox("Case Sensitive", value=r.caseSensitive)

            async def save() -> None:
                try:
                    new_rule = Rule(
                        id=r.id if is_edit else None,
                        imapConfigName=str(account_sel.value or ""),
                        conditionSubject=ConditionSubject(field_sel.value),
                        conditionOperator=ConditionOperator(op_sel.value),
                        conditionValue=str(value_inp.value or ""),
                        actionType=ActionType(action_sel.value),
                        destValue=str(dest_inp.value or ""),
                        caseSensitive=bool(case_chk.value),
                    )
                    if not new_rule.imapConfigName:
                        ui.notify("Account is required", type="negative")
                        return
                    db.save_rule(new_rule)
                    ui.notify("Saved", type="positive")
                    dialog.close()
                    rules_table.refresh()
                except Exception as exc:
                    ui.notify(f"Error: {exc}", type="negative")

            with ui.row().classes("justify-end q-mt-md q-gutter-xs"):
                ui.button("Cancel", on_click=dialog.close).props("flat").classes(
                    "text-grey-6"
                )
                ui.button("Save", on_click=save).props("color=primary")

        dialog.open()

    async def confirm_delete(rule_id: Optional[int]) -> None:
        if rule_id is None:
            return
        with ui.dialog() as dlg, ui.card():
            ui.label("Delete this rule?").classes("text-h6 q-mb-md")
            with ui.row().classes("justify-end q-gutter-xs"):
                ui.button("Cancel", on_click=dlg.close).props("flat").classes(
                    "text-grey-6"
                )

                async def do_delete() -> None:
                    db.delete_rule(rule_id)
                    dlg.close()
                    ui.notify("Deleted", type="positive")
                    rules_table.refresh()

                ui.button("Delete", on_click=do_delete).props("color=negative")
        dlg.open()

    with base_layout("Rules"):
        with ui.column().classes("full-width").style("padding:1.25rem;"):
            rules_table()
