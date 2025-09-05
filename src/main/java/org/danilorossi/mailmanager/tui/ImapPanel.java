package org.danilorossi.mailmanager.tui;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.Button;
import com.googlecode.lanterna.gui2.CheckBox;
import com.googlecode.lanterna.gui2.ComboBox;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.EmptySpace;
import com.googlecode.lanterna.gui2.GridLayout;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.TextBox;
import com.googlecode.lanterna.gui2.Window;
import com.googlecode.lanterna.gui2.table.Table;
import java.util.List;
import java.util.Objects;
import lombok.NonNull;
import lombok.val;
import org.danilorossi.mailmanager.MailManager;
import org.danilorossi.mailmanager.helpers.LangUtils;
import org.danilorossi.mailmanager.model.ImapConfig;

public class ImapPanel extends Panel {

  private Table<String> table;
  private final MultiWindowTextGUI gui;
  private final MailManager mailManager;

  public ImapPanel(@NonNull final MultiWindowTextGUI gui, @NonNull final MailManager mailManager) {
    this.gui = gui;
    this.mailManager = mailManager;
  }

  public ImapPanel build() {

    setLayoutManager(new LinearLayout(Direction.VERTICAL));
    addComponent(new Label("Gestione Server IMAP").addStyle(SGR.BOLD));
    table = new Table<String>("#", "Nome", "Host", "Porta", "Username", "Inbox");
    table.setPreferredSize(new TerminalSize(100, 16));
    table.setSelectAction(() -> editSelectedItem());
    addComponent(table);
    addComponent(new EmptySpace(new TerminalSize(0, 1)));
    refreshTable();
    val actions = new Panel(new LinearLayout(Direction.HORIZONTAL));
    actions.addComponent(
        new Button(
            "Aggiungi",
            () -> {
              val cfg = openItemDialog(null);
              if (cfg != null) {
                mailManager.getImaps().add(cfg);
                mailManager.saveImaps();
                refreshTable();
                focus();
              }
            }));
    actions.addComponent(new EmptySpace(new TerminalSize(1, 1)));
    actions.addComponent(new Button("Modifica", () -> editSelectedItem()));
    actions.addComponent(new EmptySpace(new TerminalSize(1, 1)));
    actions.addComponent(new Button("Elimina", () -> delSelectedItem()));
    actions.addComponent(new EmptySpace(new TerminalSize(2, 1)));
    actions.addComponent(new Button("Test connessione", () -> testSelectedImap()));

    addComponent(actions);
    return this;
  }

  public void focus() {
    if (table == null) return;
    table.takeFocus();
  }

  public void editSelectedItem() {
    if (table == null) return;
    val sel = table.getSelectedRow();
    if (sel < 0 || sel >= mailManager.getImaps().size()) {
      TuiUtils.info(gui, "Seleziona un server. ");
      return;
    }
    val cur = mailManager.getImaps().get(sel);
    val updated = openItemDialog(cur);
    if (updated != null) {
      mailManager.getImaps().set(sel, updated);
      mailManager.saveImaps();
      refreshTable();
      focus();
    }
  }

  public ImapConfig openItemDialog(final ImapConfig existing) {
    val dialog =
        new BasicWindow(existing == null ? "Aggiungi Server IMAP" : "Modifica Server IMAP");
    dialog.setHints(List.of(Window.Hint.CENTERED, Window.Hint.MODAL));

    // fa sì che premendo ESC la finestra si chiuda
    // equivalente al click su "Annulla"
    dialog.setCloseWindowWithEscape(true);

    val root = new Panel(new GridLayout(2));
    // leggermente più alto per far spazio al CheckBox
    root.setPreferredSize(new TerminalSize(90, 14));

    val tbName =
        TuiUtils.requiredTextBox(
            40, existing != null ? LangUtils.nullToEmpty(existing.getName()) : "");
    val tbHost =
        TuiUtils.requiredTextBox(
            50, existing != null ? LangUtils.nullToEmpty(existing.getHost()) : "");
    val tbPort =
        TuiUtils.portBox(existing != null ? LangUtils.nullToEmpty(existing.getPort()) : "993");
    val tbUser =
        TuiUtils.requiredTextBox(
            50, existing != null ? LangUtils.nullToEmpty(existing.getUsername()) : "");
    val tbPass = new TextBox(new TerminalSize(50, 1)).setMask('*');
    if (existing != null) tbPass.setText(LangUtils.nullToEmpty(existing.getPassword()));
    val tbInbox =
        TuiUtils.requiredTextBox(
            50, existing != null ? LangUtils.nullToEmpty(existing.getInboxFolder()) : "INBOX");

    // NEW: CheckBox per SpamAssassin
    val cbSpam =
        new CheckBox("Usa SpamAssassin")
            .setChecked(existing != null && existing.isUseSpamAssassin());
    val cbSpamAction = new ComboBox<ImapConfig.SpamAction>(ImapConfig.SpamAction.values());
    // default/valore esistente
    cbSpamAction.setSelectedItem(
        existing != null ? existing.getSpamAction() : ImapConfig.SpamAction.DELETE);

    // TextBox per cartella SPAM (solo se MOVE)
    val tbSpamFolder = new TextBox(new TerminalSize(30, 1));
    tbSpamFolder.setText(existing != null ? existing.getSpamFolder() : "Junk");

    val btnBrowseSpam =
        new Button(
            "Sfoglia…",
            () -> {
              // Costruisce una config temporanea con i valori correnti del form
              val tmp =
                  ImapConfig.builder()
                      .name(tbName.getText().trim())
                      .host(tbHost.getText().trim())
                      .port(tbPort.getText().trim())
                      .username(tbUser.getText().trim())
                      .password(tbPass.getText())
                      .inboxFolder(
                          tbInbox.getText().trim().isEmpty() ? "INBOX" : tbInbox.getText().trim())
                      .useSpamAssassin(cbSpam.isChecked())
                      .spamAction(cbSpamAction.getSelectedItem())
                      .spamFolder(
                          LangUtils.emptyString(tbSpamFolder.getText())
                              ? "Junk"
                              : tbSpamFolder.getText().trim())
                      .build();

              val folders = TuiUtils.fetchImapFolders(gui, tmp); // operazione bloccante
              if (folders == null) return;
              if (folders.isEmpty()) {
                TuiUtils.info(gui, "Nessuna cartella messaggi trovata.");
                return;
              }
              val chosen =
                  TuiUtils.chooseFromList(
                      gui, "Seleziona cartella SPAM", folders, tbSpamFolder.getText());
              if (chosen != null) tbSpamFolder.setText(chosen);
            });

    // Abilita/disabilita in base alla selezione
    final Runnable updateSpamFolderEnabled =
        () -> {
          boolean enable =
              cbSpam.isChecked() && cbSpamAction.getSelectedItem() == ImapConfig.SpamAction.MOVE;
          tbSpamFolder.setEnabled(enable);
          btnBrowseSpam.setEnabled(enable); // <— AGGIUNTA
        };
    cbSpamAction.addListener((comboBox, prev, curr) -> updateSpamFolderEnabled.run());
    cbSpam.addListener(checked -> updateSpamFolderEnabled.run());
    updateSpamFolderEnabled.run();

    if (existing != null) {
      tbName.setText(LangUtils.nullToEmpty(existing.getName()));
      tbHost.setText(LangUtils.nullToEmpty(existing.getHost()));
      tbPort.setText(LangUtils.nullToEmpty(existing.getPort()));
      tbUser.setText(LangUtils.nullToEmpty(existing.getUsername()));
      tbPass.setText(LangUtils.nullToEmpty(existing.getPassword()));
      tbInbox.setText(LangUtils.nullToEmpty(existing.getInboxFolder()));
    }

    // rows
    root.addComponent(new Label("Nome:"));
    root.addComponent(tbName);
    root.addComponent(new Label("Host:"));
    root.addComponent(tbHost);
    root.addComponent(new Label("Porta:"));
    root.addComponent(tbPort);
    root.addComponent(new Label("Username:"));
    root.addComponent(tbUser);
    root.addComponent(new Label("Password:"));
    root.addComponent(tbPass);
    root.addComponent(new Label("Cartella Inbox:"));
    val inboxRow = new Panel(new LinearLayout(Direction.HORIZONTAL));
    inboxRow.addComponent(tbInbox);
    inboxRow.addComponent(new EmptySpace(new TerminalSize(1, 1)));
    inboxRow.addComponent(
        new Button(
            "Sfoglia…",
            () -> {
              val tmp =
                  ImapConfig.builder()
                      .name(tbName.getText().trim())
                      .host(tbHost.getText().trim())
                      .port(tbPort.getText().trim())
                      .username(tbUser.getText().trim())
                      .password(tbPass.getText())
                      .inboxFolder(
                          tbInbox.getText().trim().isEmpty() ? "INBOX" : tbInbox.getText().trim())
                      // NEW: passa il valore anche al config temporaneo
                      .useSpamAssassin(cbSpam.isChecked())
                      .spamAction(cbSpamAction.getSelectedItem())
                      .spamFolder(
                          LangUtils.emptyString(tbSpamFolder.getText())
                              ? "Junk"
                              : tbSpamFolder.getText().trim())
                      .build();
              val folders = TuiUtils.fetchImapFolders(gui, tmp); // operazione bloccante
              if (folders == null) return;
              if (folders.isEmpty()) {
                TuiUtils.info(gui, "Nessuna cartella messaggi trovata.");
                return;
              }
              val chosen =
                  TuiUtils.chooseFromList(gui, "Seleziona cartella", folders, tbInbox.getText());
              if (chosen != null) tbInbox.setText(chosen);
            }));
    root.addComponent(inboxRow);

    // NEW: riga per SpamAssassin
    root.addComponent(new Label("SpamAssassin:"));
    root.addComponent(cbSpam);
    // riga: Azione SPAM
    root.addComponent(new Label("Azione SPAM:"));
    root.addComponent(cbSpamAction);

    // riga: Cartella SPAM (solo se MOVE) + bottone Sfoglia…
    root.addComponent(new Label("Cartella SPAM:"));
    val spamFolderRow = new Panel(new LinearLayout(Direction.HORIZONTAL));
    spamFolderRow.addComponent(tbSpamFolder);
    spamFolderRow.addComponent(new EmptySpace(new TerminalSize(1, 1)));
    spamFolderRow.addComponent(btnBrowseSpam);
    root.addComponent(spamFolderRow);

    val buttons = new Panel(new LinearLayout(Direction.HORIZONTAL));
    val result = new ImapConfig[1];

    buttons.addComponent(
        new Button(
            "Test",
            () -> {
              if (!TuiUtils.validateRequired(gui, "Nome", tbName)) return;
              if (!TuiUtils.validateRequired(gui, "Host", tbHost)) return;
              if (!TuiUtils.validateRequired(gui, "Porta", tbPort)) return;
              if (!TuiUtils.validatePort(gui, tbPort)) return;
              if (!TuiUtils.validateRequired(gui, "Username", tbUser)) return;

              val err =
                  TuiUtils.tryTestImap(
                      tbHost.getText(), tbPort.getText(), tbUser.getText(), tbPass.getText());
              if (err == null) TuiUtils.info(gui, "Connessione IMAP riuscita.");
              else TuiUtils.error(gui, "Errore IMAP: " + err);
            }));

    buttons.addComponent(new EmptySpace(new TerminalSize(2, 1)));
    buttons.addComponent(
        new Button(
            "OK",
            () -> {
              if (!TuiUtils.validateRequired(gui, "Nome", tbName)) return;
              if (!TuiUtils.validateRequired(gui, "Host", tbHost)) return;
              if (!TuiUtils.validateRequired(gui, "Porta", tbPort)) return;
              if (!TuiUtils.validatePort(gui, tbPort)) return;
              if (!TuiUtils.validateRequired(gui, "Username", tbUser)) return;
              if (!TuiUtils.validateRequired(gui, "Inbox", tbInbox)) return;
              if (cbSpam.isChecked()
                  && cbSpamAction.getSelectedItem() == ImapConfig.SpamAction.MOVE
                  && LangUtils.emptyString(tbSpamFolder.getText().trim())) {
                TuiUtils.error(gui, "Specificare la Cartella SPAM per l'azione 'Sposta'.");
                return;
              }

              val name = tbName.getText().trim();
              val host = tbHost.getText().trim();
              val port = tbPort.getText().trim();
              val user = tbUser.getText().trim();
              val pass = tbPass.getText(); // può essere vuota
              val inbox = tbInbox.getText().trim().isEmpty() ? "INBOX" : tbInbox.getText().trim();

              // Unicità nome
              val nameTaken =
                  mailManager.getImaps().stream()
                      .anyMatch(
                          c -> !Objects.equals(existing, c) && Objects.equals(name, c.getName()));
              if (nameTaken) {
                TuiUtils.error(gui, "Esiste già un server con questo Nome.");
                return;
              }

              result[0] =
                  ImapConfig.builder()
                      .name(name)
                      .host(host)
                      .port(port)
                      .username(user)
                      .password(pass)
                      .inboxFolder(inbox)
                      // NEW: salva il flag
                      .useSpamAssassin(cbSpam.isChecked())
                      .spamAction(cbSpamAction.getSelectedItem())
                      .spamFolder(
                          tbSpamFolder.getText().trim().isEmpty()
                              ? "Junk"
                              : tbSpamFolder.getText().trim())
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
    tbName.takeFocus();
    gui.addWindowAndWait(dialog);
    return result[0];
  }

  private void testSelectedImap() {
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

  public void delSelectedItem() {
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
}
