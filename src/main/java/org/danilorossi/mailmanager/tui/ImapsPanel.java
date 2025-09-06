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
import java.util.Objects;
import lombok.NonNull;
import lombok.val;
import org.danilorossi.mailmanager.MailManager;
import org.danilorossi.mailmanager.helpers.LangUtils;
import org.danilorossi.mailmanager.model.ImapConfig;

public class ImapsPanel extends Panel implements IListComponent<ImapConfig> {

  private Table<String> table;
  private final MultiWindowTextGUI gui;
  private final MailManager mailManager;

  public ImapsPanel(@NonNull final MultiWindowTextGUI gui, @NonNull final MailManager mailManager) {
    this.gui = gui;
    this.mailManager = mailManager;
  }

  @Override
  public ImapsPanel build() {

    setLayoutManager(new LinearLayout(Direction.VERTICAL));
    addComponent(new Label("Gestione Server IMAP").addStyle(SGR.BOLD));
    table = new Table<String>("#", "Nome", "Host", "Porta", "Username", "Inbox");
    table.setPreferredSize(new TerminalSize(100, 16));
    table.setSelectAction(() -> editSelectedItemAction());
    addComponent(table);
    addComponent(new EmptySpace(new TerminalSize(0, 1)));
    refreshTable();
    val actions = new Panel(new LinearLayout(Direction.HORIZONTAL));
    actions.addComponent(new Button("Aggiungi", () -> addItemAction()));
    actions.addComponent(new EmptySpace(new TerminalSize(1, 1)));
    actions.addComponent(new Button("Modifica", () -> editSelectedItemAction()));
    actions.addComponent(new EmptySpace(new TerminalSize(1, 1)));
    actions.addComponent(new Button("Elimina", () -> delSelectedItemAction()));
    actions.addComponent(new EmptySpace(new TerminalSize(2, 1)));
    actions.addComponent(new Button("Test connessione", () -> testSelectedImapAction()));

    addComponent(actions);
    return this;
  }

  @Override
  public void focus() {
    if (table == null) return;
    table.takeFocus();
  }

  @Override
  public void addItemAction() {
    val cfg = openItemDialog(null);
    if (cfg == null) return;
    mailManager.getImaps().add(cfg);
    mailManager.saveImaps();
    refreshTable();
  }

  @Override
  public void editSelectedItemAction() {
    if (table == null) return;
    val sel = table.getSelectedRow();
    if (sel < 0 || sel >= mailManager.getImaps().size()) {
      TuiUtils.info(gui, "Seleziona un server. ");
      return;
    }
    val current = mailManager.getImaps().get(sel);
    val updated = openItemDialog(current);
    if (updated == null) return;

    mailManager.getImaps().set(sel, updated);
    mailManager.saveImaps();
    refreshTable();
  }

  private void testSelectedImapAction() {
    if (table == null) return;
    val sel = table.getSelectedRow();
    if (sel < 0 || sel >= mailManager.getImaps().size()) {
      TuiUtils.info(gui, "Seleziona un server. ");
      return;
    }
    val cfg = mailManager.getImaps().get(sel);
    val err = TuiUtils.tryTestImap(cfg);
    if (err == null) TuiUtils.info(gui, "Connessione IMAP riuscita.");
    else TuiUtils.error(gui, "Errore IMAP: " + err);
    focus();
  }

  public void refreshTable() {
    val prev = table.getSelectedRow();
    table.getTableModel().clear();
    val imaps = mailManager.getImaps();
    for (int i = 0; i < imaps.size(); i++) {
      val c = imaps.get(i);
      table
          .getTableModel()
          .addRow(
              String.valueOf(i + 1),
              LangUtils.nullToEmpty(c.getName()),
              LangUtils.nullToEmpty(c.getHost()),
              LangUtils.nullToEmpty(c.getPort()),
              LangUtils.nullToEmpty(c.getUsername()),
              LangUtils.nullToEmpty(c.getInboxFolder()));
    }
    if (!imaps.isEmpty()) {
      val newSel = Math.max(0, Math.min(prev, imaps.size() - 1));
      table.setSelectedRow(newSel);
    }
    focus();
  }

  public void delSelectedItemAction() {
    if (table == null) return;
    if (mailManager.getImaps().isEmpty()) {
      TuiUtils.info(gui, "Nessun server da eliminare.");
      return;
    }
    int sel = table.getSelectedRow();
    if (sel < 0 || sel >= mailManager.getImaps().size()) {
      TuiUtils.info(gui, "Seleziona un server. ");
      return;
    }
    // avvisa se ci sono regole che puntano a quel server
    val name = mailManager.getImaps().get(sel).getName();
    val usedInRules =
        mailManager.getRules().stream().anyMatch(r -> Objects.equals(r.getImapConfigName(), name));
    if (usedInRules) {
      if (!TuiUtils.confirm(
          gui,
          "Attenzione",
          LangUtils.s("Ci sono regole associate a \"{}\".\nEliminare comunque?", name))) return;
    } else {
      if (!TuiUtils.confirm(gui, "Conferma", "Eliminare il server selezionato?")) return;
    }
    mailManager.getImaps().remove(sel);
    mailManager.saveImaps();
    refreshTable();
    focus();
  }

  public ImapConfig openItemDialog(final ImapConfig existing) {
    return new ImapDialog(gui, mailManager, existing).build();
  }
}
