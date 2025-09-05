package org.danilorossi.mailmanager.tui;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.Button;
import com.googlecode.lanterna.gui2.ComboBox;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.EmptySpace;
import com.googlecode.lanterna.gui2.GridLayout;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.Window;
import com.googlecode.lanterna.gui2.table.Table;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.NonNull;
import lombok.val;
import org.danilorossi.mailmanager.MailManager;
import org.danilorossi.mailmanager.helpers.LangUtils;
import org.danilorossi.mailmanager.model.ActionType;
import org.danilorossi.mailmanager.model.ConditionOperator;
import org.danilorossi.mailmanager.model.ConditionSubject;
import org.danilorossi.mailmanager.model.Rule;

public class RulesPanel extends Panel {

  private Table<String> table;
  private final MultiWindowTextGUI gui;
  private final MailManager mailManager;

  public RulesPanel(@NonNull final MultiWindowTextGUI gui, @NonNull final MailManager mailManager) {
    this.gui = gui;
    this.mailManager = mailManager;
  }

  public RulesPanel build() {
    setLayoutManager(new LinearLayout(Direction.VERTICAL));
    addComponent(new Label("Gestione Regole").addStyle(SGR.BOLD));

    table = new Table<String>("#", "IMAP", "Soggetto", "Operatore", "Valore", "Azione", "Dest");

    table.setPreferredSize(new TerminalSize(110, 16));
    table.setSelectAction(() -> editSelectedItem());

    refreshTable();

    addComponent(table);
    addComponent(new EmptySpace(new TerminalSize(0, 1)));

    val actions = new Panel(new LinearLayout(Direction.HORIZONTAL));
    actions.addComponent(
        new Button(
            "Aggiungi",
            () -> {
              if (mailManager.getImaps().isEmpty()) {
                TuiUtils.info(gui, "Configura almeno un server IMAP prima di creare regole.");
                return;
              }
              val r = openItemDialog(null);
              if (r != null) {
                mailManager.getRules().add(r);
                mailManager.saveRules();
                refreshTable();
                focus();
              }
            }));
    actions.addComponent(new EmptySpace(new TerminalSize(1, 1)));
    actions.addComponent(new Button("Modifica", () -> editSelectedItem()));
    actions.addComponent(new EmptySpace(new TerminalSize(1, 1)));
    actions.addComponent(new Button("Elimina", () -> delSelectedItem()));

    addComponent(actions);
    return this;
  }

  public void editSelectedItem() {
    if (table == null) return;
    val sel = table.getSelectedRow();
    if (sel < 0 || sel >= mailManager.getRules().size()) {
      TuiUtils.info(gui, "Seleziona una regola.");
      return;
    }
    val updated = openItemDialog(mailManager.getRules().get(sel));
    if (updated != null) {
      mailManager.getRules().set(sel, updated);
      mailManager.saveRules();
      refreshTable();
      focus();
    }
  }

  public void delSelectedItem() {
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
    focus();
  }

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

  public Rule openItemDialog(final Rule existing) {
    val dialog = new BasicWindow(existing == null ? "Aggiungi Regola" : "Modifica Regola");
    dialog.setHints(List.of(Window.Hint.CENTERED, Window.Hint.MODAL));

    // fa sì che premendo ESC la finestra si chiuda
    // equivalente al click su "Annulla"
    dialog.setCloseWindowWithEscape(true);

    val root = new Panel(new GridLayout(2));
    root.setPreferredSize(new TerminalSize(100, 14));

    // Se non ci sono IMAP, blocca (ulteriore safety, oltre al bottone Aggiungi)
    if (mailManager.getImaps().isEmpty()) {
      TuiUtils.info(gui, "Configura almeno un server IMAP prima di creare regole.");
      return null;
    }

    // IMAP name combo
    val imapNames = new ArrayList<String>();
    for (val c : mailManager.getImaps()) imapNames.add(c.getName());
    val cbImap = new ComboBox<>(imapNames);

    val cbSubject = new ComboBox<>(ConditionSubject.values());
    val cbOperator = new ComboBox<>(ConditionOperator.values());
    val tbValue =
        TuiUtils.requiredTextBox(
            60, existing != null ? LangUtils.nullToEmpty(existing.getConditionValue()) : "");
    val cbAction = new ComboBox<>(ActionType.values());
    val tbDest =
        TuiUtils.requiredTextBox(
            60, existing != null ? LangUtils.nullToEmpty(existing.getDestValue()) : "");

    val btnPickFolder =
        new Button(
            "Sfoglia cartelle…",
            () -> {
              val chosenImap = cbImap.getSelectedItem();
              val cfg =
                  mailManager.getImaps().stream()
                      .filter(c -> Objects.equals(c.getName(), chosenImap))
                      .findFirst()
                      .orElse(null);
              if (cfg == null) {
                TuiUtils.info(gui, "Seleziona prima un server IMAP.");
                return;
              }
              val folders = TuiUtils.fetchImapFolders(gui, cfg); // operazione bloccante
              if (folders == null) return;
              if (folders.isEmpty()) {
                TuiUtils.info(gui, "Nessuna cartella messaggi trovata.");
                return;
              }
              val chosen =
                  TuiUtils.chooseFromList(gui, "Seleziona cartella", folders, tbDest.getText());
              if (chosen != null) tbDest.setText(chosen);
            });

    if (existing != null) {
      cbImap.setSelectedItem(existing.getImapConfigName());
      cbSubject.setSelectedItem(existing.getConditionSubject());
      cbOperator.setSelectedItem(existing.getConditionOperator());
      tbValue.setText(LangUtils.nullToEmpty(existing.getConditionValue()));
      cbAction.setSelectedItem(existing.getActionType());
      tbDest.setText(LangUtils.nullToEmpty(existing.getDestValue()));
    } else {
      if (!imapNames.isEmpty()) cbImap.setSelectedIndex(0);
      cbSubject.setSelectedIndex(ConditionSubject.FROM.ordinal());
      cbOperator.setSelectedIndex(ConditionOperator.CONTAINS.ordinal());
      cbAction.setSelectedIndex(ActionType.MOVE.ordinal());
    }

    // abilita/disabilita destinazione in base all'azione
    final Runnable refreshDestEnabled =
        () -> {
          boolean needDest = cbAction.getSelectedItem() != ActionType.DELETE;
          tbDest.setEnabled(needDest);
          btnPickFolder.setEnabled(needDest);
        };
    cbAction.addListener((idx, prev, byUser) -> refreshDestEnabled.run());
    refreshDestEnabled.run();

    // layout
    root.addComponent(new Label("Server IMAP:"));
    root.addComponent(cbImap);
    root.addComponent(new Label("Soggetto condizione:"));
    root.addComponent(cbSubject);
    root.addComponent(new Label("Operatore:"));
    root.addComponent(cbOperator);
    root.addComponent(new Label("Valore condizione:"));
    root.addComponent(tbValue);
    root.addComponent(new Label("Azione:"));
    root.addComponent(cbAction);
    root.addComponent(new Label("Valore azione (cartella se MOVE/COPY):"));
    val destRow = new Panel(new LinearLayout(Direction.HORIZONTAL));
    destRow.addComponent(tbDest);
    destRow.addComponent(new EmptySpace(new TerminalSize(1, 1)));
    destRow.addComponent(btnPickFolder);
    root.addComponent(destRow);

    val buttons = new Panel(new LinearLayout(Direction.HORIZONTAL));
    val result = new Rule[1];
    buttons.addComponent(
        new Button(
            "OK",
            () -> {
              // Validazioni base
              val imapName = (String) cbImap.getSelectedItem();
              if (imapName == null || imapName.isBlank()) {
                TuiUtils.error(gui, "Seleziona un server IMAP.");
                return;
              }
              if (!TuiUtils.validateRequired(gui, "Valore condizione", tbValue)) return;

              val action = cbAction.getSelectedItem();
              val needDest = action != ActionType.DELETE;

              if (needDest) {
                if (!TuiUtils.validateRequired(gui, "Destinazione", tbDest)) return;
              }

              val dest = tbDest.getText().trim();
              result[0] =
                  Rule.builder()
                      .imapConfigName(imapName)
                      .conditionSubject(cbSubject.getSelectedItem())
                      .conditionOperator(cbOperator.getSelectedItem())
                      .conditionValue(tbValue.getText().trim())
                      .actionType(action)
                      .destValue(needDest ? dest : null)
                      .build();
              dialog.close();
            }));

    buttons.addComponent(new EmptySpace(new TerminalSize(1, 1)));
    buttons.addComponent(new Button("Annulla", dialog::close));

    val outer = new Panel(new LinearLayout(Direction.VERTICAL));
    outer.addComponent(root);
    outer.addComponent(new EmptySpace(new TerminalSize(0, 1)));
    outer.addComponent(buttons);

    dialog.setComponent(outer);
    cbImap.takeFocus();
    gui.addWindowAndWait(dialog);
    return result[0];
  }

  public void focus() {
    if (table == null) return;
    table.takeFocus();
  }
}
