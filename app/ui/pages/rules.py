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
                ui.label("Regole").classes("text-h6")
                ui.button("+ Aggiungi", on_click=lambda: open_dialog(account_names)).props(
                    "color=primary size=sm"
                )

            if not rules:
                ui.label("Nessuna regola").classes("text-grey-6 text-center q-pa-xl full-width")
                return

            with ui.row().classes(
                "q-px-sm q-py-xs text-grey-6 full-width text-caption "
                "text-uppercase text-weight-bold"
            ):
                ui.label("Account").style("width:130px;")
                ui.label("Campo").style("width:90px;")
                ui.label("Operatore").style("width:120px;")
                ui.label("Valore").style("flex:1;")
                ui.label("Azione").style("width:110px;")
                ui.label("Dest.").style("width:100px;")
                ui.label("").style("width:100px;")
            ui.separator()

            for r in rules:
                with ui.row().classes("items-center q-px-sm q-py-xs full-width"):
                    ui.label(r.imap_config_name).classes("text-weight-bold").style("width:130px;")
                    ui.label(r.condition_subject.value).classes("text-grey-6").style("width:90px;")
                    ui.label(r.condition_operator.value).classes("text-grey-6").style(
                        "width:120px;"
                    )
                    ui.label(r.condition_value).style("flex:1;")
                    ui.label(r.action_type.value).classes("text-grey-6").style("width:110px;")
                    ui.label(r.dest_value or "—").classes("text-grey-6").style("width:100px;")
                    with ui.row().style("width:100px;").classes("q-gutter-xs"):
                        ui.button(
                            "Modifica",
                            on_click=lambda r=r, names=account_names: open_dialog(names, r),
                        ).props("flat size=sm")
                        ui.button(
                            "Elim.",
                            on_click=lambda r=r: confirm_delete(r.id),
                        ).props("flat size=sm color=negative")
                ui.separator()

    async def open_dialog(account_names: list[str], rule: Rule | None = None) -> None:
        is_edit = rule is not None
        r = rule or Rule(
            imap_config_name=account_names[0] if account_names else "",
            action_type=ActionType.MOVE,
            condition_operator=ConditionOperator.CONTAINS,
            condition_subject=ConditionSubject.FROM,
        )
        title = "Modifica regola" if is_edit else "Aggiungi regola"

        with ui.dialog() as dialog, ui.card().style("width:560px;"):
            ui.label(title).classes("text-h6 q-mb-sm")

            account_sel = ui.select(
                account_names or ["—"],
                value=r.imap_config_name or (account_names[0] if account_names else ""),
                label="Account",
            ).classes("full-width")

            with ui.grid(columns=2).classes("full-width").style("gap:12px;"):
                field_sel = ui.select(_FIELDS, value=r.condition_subject.value, label="Campo")
                op_sel = ui.select(_OPERATORS, value=r.condition_operator.value, label="Operatore")

            value_inp = ui.input("Valore", value=r.condition_value).classes("full-width")

            with ui.grid(columns=2).classes("full-width").style("gap:12px;"):
                action_sel = ui.select(_ACTIONS, value=r.action_type.value, label="Azione")
                dest_inp = ui.input("Destinazione / Etichetta", value=r.dest_value)

            case_chk = ui.checkbox("Distingui maiuscole/minuscole", value=r.case_sensitive)

            async def save() -> None:
                try:
                    new_rule = Rule(
                        id=r.id if is_edit else None,
                        imap_config_name=str(account_sel.value or ""),
                        condition_subject=ConditionSubject(field_sel.value),
                        condition_operator=ConditionOperator(op_sel.value),
                        condition_value=str(value_inp.value or ""),
                        action_type=ActionType(action_sel.value),
                        dest_value=str(dest_inp.value or ""),
                        case_sensitive=bool(case_chk.value),
                    )
                    if not new_rule.imap_config_name:
                        ui.notify("L'account è obbligatorio", type="negative")
                        return
                    db.save_rule(new_rule)
                    ui.notify("Salvato", type="positive")
                    dialog.close()
                    rules_table.refresh()
                except Exception as exc:
                    ui.notify(f"Errore: {exc}", type="negative")

            with ui.row().classes("justify-end q-mt-md q-gutter-xs"):
                ui.button("Annulla", on_click=dialog.close).props("flat").classes("text-grey-6")
                ui.button("Salva", on_click=save).props("color=primary")

        dialog.open()

    async def confirm_delete(rule_id: int | None) -> None:
        if rule_id is None:
            return
        with ui.dialog() as dlg, ui.card():
            ui.label("Eliminare questa regola?").classes("text-h6 q-mb-md")
            with ui.row().classes("justify-end q-gutter-xs"):
                ui.button("Annulla", on_click=dlg.close).props("flat").classes("text-grey-6")

                async def do_delete() -> None:
                    db.delete_rule(rule_id)
                    dlg.close()
                    ui.notify("Eliminato", type="positive")
                    rules_table.refresh()

                ui.button("Elimina", on_click=do_delete).props("color=negative")
        dlg.open()

    with base_layout("Regole"):
        with ui.column().classes("full-width").style("padding:1.25rem;"):
            rules_table()
