package org.danilorossi.mailmanager.tui;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.Button;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.EmptySpace;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.table.Table;
import lombok.NonNull;
import lombok.val;
import org.danilorossi.mailmanager.MailManager;
import org.danilorossi.mailmanager.helpers.LangUtils;
import org.danilorossi.mailmanager.model.Rule;

public class RulesPanel extends Panel implements IListComponent<Rule> {

  private Table<String> table;
  private final MultiWindowTextGUI gui;
  private final MailManager mailManager;

  public RulesPanel(@NonNull final MultiWindowTextGUI gui, @NonNull final MailManager mailManager) {
    this.gui = gui;
    this.mailManager = mailManager;
  }

  @Override
  public RulesPanel build() {
    setLayoutManager(new LinearLayout(Direction.VERTICAL));
    addComponent(new Label("Gestione Regole").addStyle(SGR.BOLD));

    table = new Table<String>("#", "IMAP", "Soggetto", "Operatore", "Valore", "Azione", "Dest");

    table.setPreferredSize(new TerminalSize(110, 16));
    table.setSelectAction(() -> editSelectedItemAction());

    refreshTable();

    addComponent(table);
    addComponent(new EmptySpace(new TerminalSize(0, 1)));

    val actions = new Panel(new LinearLayout(Direction.HORIZONTAL));
    actions.addComponent(new Button("Aggiungi", () -> addItemAction()));
    actions.addComponent(new EmptySpace(new TerminalSize(1, 1)));
    actions.addComponent(new Button("Modifica", () -> editSelectedItemAction()));
    actions.addComponent(new EmptySpace(new TerminalSize(1, 1)));
    actions.addComponent(new Button("Elimina", () -> delSelectedItemAction()));

    addComponent(actions);
    return this;
  }

  @Override
  public void addItemAction() {
    if (mailManager.getImaps().isEmpty()) {
      TuiUtils.info(gui, "Configura almeno un server IMAP prima di creare regole.");
      return;
    }
    val r = openItemDialog(null);
    if (r == null) return;
    mailManager.getRules().add(r);
    mailManager.saveRules();
    refreshTable();
  }

  @Override
  public void editSelectedItemAction() {
    if (table == null) return;
    val sel = table.getSelectedRow();
    if (sel < 0 || sel >= mailManager.getRules().size()) {
      TuiUtils.info(gui, "Seleziona una regola.");
      return;
    }
    val updated = openItemDialog(mailManager.getRules().get(sel));
    if (updated == null) return;
    mailManager.getRules().set(sel, updated);
    mailManager.saveRules();
    refreshTable();
  }

  @Override
  public void delSelectedItemAction() {
    if (mailManager.getRules().isEmpty()) {
      TuiUtils.info(gui, "Nessuna regola da eliminare. ");
      return;
    }
    val sel = table.getSelectedRow();
    if (sel < 0 || sel >= mailManager.getRules().size()) {
      TuiUtils.info(gui, "Seleziona una regola.");
      return;
    }
    if (!TuiUtils.confirm(gui, "Conferma", "Eliminare la regola selezionata?")) return;

    mailManager.getRules().remove(sel);
    mailManager.saveRules();
    refreshTable();
  }

  @Override
  public void refreshTable() {
    val prev = table.getSelectedRow();
    table.getTableModel().clear();
    val rules = mailManager.getRules();
    for (int i = 0; i < rules.size(); i++) {
      val r = rules.get(i);
      table
          .getTableModel()
          .addRow(
              String.valueOf(i + 1),
              LangUtils.nullToEmpty(r.getImapConfigName()),
              String.valueOf(r.getConditionSubject()),
              String.valueOf(r.getConditionOperator()),
              LangUtils.nullToEmpty(r.getConditionValue()),
              String.valueOf(r.getActionType()),
              LangUtils.nullToEmpty(r.getDestValue()));
    }
    if (!rules.isEmpty()) {
      val newSel = Math.max(0, Math.min(prev, rules.size() - 1));
      table.setSelectedRow(newSel);
    }
    focus();
  }

  @Override
  public Rule openItemDialog(final Rule existing) {
    return new RuleDialog(gui, mailManager, existing).build();
  }

  @Override
  public void focus() {
    if (table == null) return;
    table.takeFocus();
  }
}
