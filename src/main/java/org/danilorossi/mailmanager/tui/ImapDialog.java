package org.danilorossi.mailmanager.tui;

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
import java.util.List;
import java.util.Objects;
import lombok.NonNull;
import lombok.val;
import org.danilorossi.mailmanager.MailManager;
import org.danilorossi.mailmanager.helpers.LangUtils;
import org.danilorossi.mailmanager.model.ImapConfig;
import org.danilorossi.mailmanager.model.ImapConfig.SpamAction;

public class ImapDialog extends BasicWindow {

  private ImapConfig config = null;
  private final MultiWindowTextGUI gui;
  private final MailManager mailManager;

  private TextBox tbName = null;
  private TextBox tbHost = null;
  private TextBox tbPort = null;
  private TextBox tbUser = null;
  private TextBox tbPass = null;
  private TextBox tbInbox = null;
  private TextBox tbSpamFolder = null;
  private CheckBox cbSpam = null;
  private ComboBox<SpamAction> cbSpamAction = null;
  private Button btnBrowseSpam = null;

  public ImapDialog(
      @NonNull final MultiWindowTextGUI gui,
      @NonNull final MailManager mailManager,
      final ImapConfig config) {
    this.gui = gui;
    this.mailManager = mailManager;
    this.config = config;
  }

  private void listImapFoldersAction(final TextBox targTextBox) {
    // Costruisce una config temporanea con i valori correnti del form
    val tmp =
        ImapConfig.builder()
            .name(tbName.getText().trim())
            .host(tbHost.getText().trim())
            .port(tbPort.getText().trim())
            .username(tbUser.getText().trim())
            .password(tbPass.getText())
            .inboxFolder(LangUtils.empty(tbInbox.getText()) ? "INBOX" : tbInbox.getText().trim())
            .useSpamAssassin(cbSpam.isChecked())
            .spamAction(cbSpamAction.getSelectedItem())
            .spamFolder(
                LangUtils.empty(tbSpamFolder.getText()) ? "Junk" : tbSpamFolder.getText().trim())
            .build();

    val folders = TuiUtils.fetchImapFolders(gui, tmp); // operazione bloccante
    if (folders == null) return;
    if (folders.isEmpty()) {
      TuiUtils.info(gui, "Nessuna cartella messaggi trovata.");
      return;
    }
    val chosen =
        TuiUtils.chooseFromList(gui, "Seleziona cartella SPAM", folders, targTextBox.getText());
    if (chosen != null) targTextBox.setText(chosen);
  }

  private void tryImapServerAction() {
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
  }

  private void confirmAction(@NonNull final ImapConfig[] result) {
    if (!TuiUtils.validateRequired(gui, "Nome", tbName)) return;
    if (!TuiUtils.validateRequired(gui, "Host", tbHost)) return;
    if (!TuiUtils.validateRequired(gui, "Porta", tbPort)) return;
    if (!TuiUtils.validatePort(gui, tbPort)) return;
    if (!TuiUtils.validateRequired(gui, "Username", tbUser)) return;
    if (!TuiUtils.validateRequired(gui, "Inbox", tbInbox)) return;
    if (cbSpam.isChecked()
        && cbSpamAction.getSelectedItem() == ImapConfig.SpamAction.MOVE
        && LangUtils.empty(tbSpamFolder.getText().trim())) {
      TuiUtils.error(gui, "Specificare la Cartella SPAM per l'azione 'Sposta'.");
      return;
    }

    val name = tbName.getText().trim();
    val host = tbHost.getText().trim();
    val port = tbPort.getText().trim();
    val user = tbUser.getText().trim();
    val pass = tbPass.getText(); // può essere vuota
    val inbox = LangUtils.empty(tbInbox.getText()) ? "INBOX" : tbInbox.getText().trim();

    // Unicità nome
    val nameTaken =
        mailManager.getImaps().stream()
            .anyMatch(c -> !Objects.equals(config, c) && Objects.equals(name, c.getName()));
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
                LangUtils.empty(tbSpamFolder.getText()) ? "Junk" : tbSpamFolder.getText().trim())
            .build();
    close();
  }

  public ImapConfig build() {

    setTitle(config == null ? "Aggiungi Server IMAP" : "Modifica Server IMAP");
    setHints(List.of(Window.Hint.CENTERED, Window.Hint.MODAL));

    // fa sì che premendo ESC la finestra si chiuda
    // equivalente al click su "Annulla"
    setCloseWindowWithEscape(true);

    val root = new Panel(new GridLayout(2));
    // leggermente più alto per far spazio al CheckBox
    root.setPreferredSize(new TerminalSize(90, 14));

    tbName =
        TuiUtils.requiredTextBox(40, config != null ? LangUtils.nullToEmpty(config.getName()) : "");
    tbHost =
        TuiUtils.requiredTextBox(50, config != null ? LangUtils.nullToEmpty(config.getHost()) : "");
    tbPort = TuiUtils.portBox(config != null ? LangUtils.nullToEmpty(config.getPort()) : "993");
    tbUser =
        TuiUtils.requiredTextBox(
            50, config != null ? LangUtils.nullToEmpty(config.getUsername()) : "");
    tbPass = new TextBox(new TerminalSize(50, 1)).setMask('*');
    if (config != null) tbPass.setText(LangUtils.nullToEmpty(config.getPassword()));
    tbInbox =
        TuiUtils.requiredTextBox(
            50, config != null ? LangUtils.nullToEmpty(config.getInboxFolder()) : "INBOX");

    // NEW: CheckBox per SpamAssassin
    cbSpam =
        new CheckBox("Usa SpamAssassin").setChecked(config != null && config.isUseSpamAssassin());
    cbSpamAction = new ComboBox<ImapConfig.SpamAction>(ImapConfig.SpamAction.values());
    // default/valore esistente
    cbSpamAction.setSelectedItem(
        config != null ? config.getSpamAction() : ImapConfig.SpamAction.DELETE);

    // TextBox per cartella SPAM (solo se MOVE)
    tbSpamFolder = new TextBox(new TerminalSize(30, 1));
    tbSpamFolder.setText(config != null ? config.getSpamFolder() : "Junk");

    btnBrowseSpam = new Button("Sfoglia…", () -> listImapFoldersAction(tbSpamFolder));

    // Abilita/disabilita in base alla selezione
    final Runnable updateSpamFolderEnabled =
        () -> {
          // boolean enable = cbSpam.isChecked() && cbSpamAction.getSelectedItem() ==
          // ImapConfig.SpamAction.MOVE;
          boolean enable =
              cbSpam.isChecked() && cbSpamAction.getSelectedItem() == ImapConfig.SpamAction.MOVE;
          tbSpamFolder.setEnabled(enable);
          btnBrowseSpam.setEnabled(enable);
        };

    cbSpamAction.addListener((comboBox, prev, curr) -> updateSpamFolderEnabled.run());
    cbSpam.addListener(checked -> updateSpamFolderEnabled.run());
    updateSpamFolderEnabled.run();

    if (config != null) {
      tbName.setText(LangUtils.nullToEmpty(config.getName()));
      tbHost.setText(LangUtils.nullToEmpty(config.getHost()));
      tbPort.setText(LangUtils.nullToEmpty(config.getPort()));
      tbUser.setText(LangUtils.nullToEmpty(config.getUsername()));
      tbPass.setText(LangUtils.nullToEmpty(config.getPassword()));
      tbInbox.setText(LangUtils.nullToEmpty(config.getInboxFolder()));
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
    inboxRow.addComponent(new Button("Sfoglia…", () -> listImapFoldersAction(tbInbox)));
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

    buttons.addComponent(new Button("Test", () -> tryImapServerAction()));

    buttons.addComponent(new EmptySpace(new TerminalSize(2, 1)));
    buttons.addComponent(new Button("OK", () -> confirmAction(result)));

    buttons.addComponent(new EmptySpace(new TerminalSize(1, 1)));
    buttons.addComponent(new Button("Annulla", this::close));

    val outer = new Panel(new LinearLayout(Direction.VERTICAL));
    outer.addComponent(root);
    outer.addComponent(new EmptySpace(new TerminalSize(0, 1)));
    outer.addComponent(buttons);

    setComponent(outer);
    tbName.takeFocus();
    gui.addWindowAndWait(this);
    return result[0];
  }
}
