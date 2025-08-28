package org.danilorossi.mailmanager;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.*;
import com.googlecode.lanterna.gui2.dialogs.MessageDialog;
import com.googlecode.lanterna.gui2.dialogs.MessageDialogButton;
import com.googlecode.lanterna.gui2.table.Table;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import jakarta.mail.Folder;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.NonNull;
import lombok.val;
import org.danilorossi.mailmanager.helpers.LangUtils;
import org.danilorossi.mailmanager.model.ActionType;
import org.danilorossi.mailmanager.model.ConditionOperator;
import org.danilorossi.mailmanager.model.ConditionSubject;
import org.danilorossi.mailmanager.model.ImapConfig;
import org.danilorossi.mailmanager.model.Rule;

public class ConsoleTUI {

  enum ActiveTab {
    IMAPS,
    RULES
  }

  private final MailManager mailManager;

  private Panel content;
  private Label footerHint;

  private Table<String> imapsTable;
  private Table<String> rulesTable;

  private ActiveTab tab = ActiveTab.IMAPS;

  public ConsoleTUI(@NonNull MailManager mailManager) {
    this.mailManager = mailManager;
  }

  // ============================  IMAPS  ============================

  private Panel buildImapsPanel(@NonNull final MultiWindowTextGUI gui) {
    val panel = new Panel(new LinearLayout(Direction.VERTICAL));
    panel.addComponent(new Label("Gestione Server IMAP").addStyle(SGR.BOLD));

    val table = new Table<String>("#", "Nome", "Host", "Porta", "Username", "Inbox");
    this.imapsTable = table;
    table.setPreferredSize(new TerminalSize(100, 16));
    table.setSelectAction(() -> editSelectedImap(gui));

    refreshImapsTable(table);

    panel.addComponent(table);
    panel.addComponent(new EmptySpace(new TerminalSize(0, 1)));

    val actions = new Panel(new LinearLayout(Direction.HORIZONTAL));
    actions.addComponent(
        new Button(
            "Aggiungi",
            () -> {
              val cfg = openImapDialog(gui, null);
              if (cfg != null) {
                mailManager.getImaps().add(cfg);
                mailManager.saveImaps();
                refreshImapsTable(table);
              }
            }));
    actions.addComponent(new EmptySpace(new TerminalSize(1, 1)));
    actions.addComponent(new Button("Modifica", () -> editSelectedImap(gui)));
    actions.addComponent(new EmptySpace(new TerminalSize(1, 1)));
    actions.addComponent(new Button("Elimina", () -> delSelectedImap(gui)));
    actions.addComponent(new EmptySpace(new TerminalSize(2, 1)));
    actions.addComponent(new Button("Test connessione", () -> testSelectedImap(gui)));

    panel.addComponent(actions);
    return panel;
  }

  private void editSelectedImap(MultiWindowTextGUI gui) {
    if (imapsTable == null) return;
    val sel = imapsTable.getSelectedRow();
    if (sel < 0 || sel >= mailManager.getImaps().size()) {
      MessageDialog.showMessageDialog(gui, "Info", "Seleziona un server.", MessageDialogButton.OK);
      return;
    }
    val cur = mailManager.getImaps().get(sel);
    val updated = openImapDialog(gui, cur);
    if (updated != null) {
      mailManager.getImaps().set(sel, updated);
      mailManager.saveImaps();
      refreshImapsTable(imapsTable);
      imapsTable.takeFocus();
    }
  }

  private void delSelectedImap(MultiWindowTextGUI gui) {
    if (imapsTable == null) return;
    if (mailManager.getImaps().isEmpty()) {
      MessageDialog.showMessageDialog(
          gui, "Info", "Nessun server da eliminare.", MessageDialogButton.OK);
      return;
    }
    int sel = imapsTable.getSelectedRow();
    if (sel < 0 || sel >= mailManager.getImaps().size()) {
      MessageDialog.showMessageDialog(gui, "Info", "Seleziona un server.", MessageDialogButton.OK);
      return;
    }
    // avvisa se ci sono regole che puntano a quel server
    String name = mailManager.getImaps().get(sel).getName();
    boolean usedInRules =
        mailManager.getRules().stream().anyMatch(r -> Objects.equals(r.getImapConfigName(), name));
    if (usedInRules) {
      val btn =
          MessageDialog.showMessageDialog(
              gui,
              "Attenzione",
              "Ci sono regole associate a \"" + name + "\".\nEliminare comunque?",
              MessageDialogButton.Yes,
              MessageDialogButton.No);
      if (btn != MessageDialogButton.Yes) return;
    } else {
      val btn =
          MessageDialog.showMessageDialog(
              gui,
              "Conferma",
              "Eliminare il server selezionato?",
              MessageDialogButton.Yes,
              MessageDialogButton.No);
      if (btn != MessageDialogButton.Yes) return;
    }
    mailManager.getImaps().remove(sel);
    mailManager.saveImaps();
    refreshImapsTable(imapsTable);
  }

  private void testSelectedImap(MultiWindowTextGUI gui) {
    if (imapsTable == null) return;
    val sel = imapsTable.getSelectedRow();
    if (sel < 0 || sel >= mailManager.getImaps().size()) {
      MessageDialog.showMessageDialog(gui, "Info", "Seleziona un server.", MessageDialogButton.OK);
      return;
    }
    val cfg = mailManager.getImaps().get(sel);
    val err = tryTestImap(cfg.getHost(), cfg.getPort(), cfg.getUsername(), cfg.getPassword());
    if (err == null) {
      MessageDialog.showMessageDialog(
          gui, "OK", "Connessione IMAP riuscita.", MessageDialogButton.OK);
    } else {
      MessageDialog.showMessageDialog(gui, "Errore", err, MessageDialogButton.OK);
    }
  }

  private void refreshImapsTable(Table<String> table) {
    val prev = table.getSelectedRow();
    table.getTableModel().clear();
    val imaps = mailManager.getImaps();
    for (int i = 0; i < imaps.size(); i++) {
      val c = imaps.get(i);
      table
          .getTableModel()
          .addRow(
              String.valueOf(i + 1),
              nullToEmpty(c.getName()),
              nullToEmpty(c.getHost()),
              nullToEmpty(c.getPort()),
              nullToEmpty(c.getUsername()),
              nullToEmpty(c.getInboxFolder()));
    }
    if (!imaps.isEmpty()) {
      val newSel = Math.max(0, Math.min(prev, imaps.size() - 1));
      table.setSelectedRow(newSel);
    }
  }

  private ImapConfig openImapDialog(
      @NonNull final MultiWindowTextGUI gui, final ImapConfig existing) {
    val dialog =
        new BasicWindow(existing == null ? "Aggiungi Server IMAP" : "Modifica Server IMAP");
    dialog.setHints(List.of(Window.Hint.CENTERED, Window.Hint.MODAL));
    val root = new Panel(new GridLayout(2));
    root.setPreferredSize(new TerminalSize(90, 13));

    val tbName = new TextBox(new TerminalSize(40, 1));
    val tbHost = new TextBox(new TerminalSize(50, 1));
    val tbPort = new TextBox(new TerminalSize(10, 1));
    val tbUser = new TextBox(new TerminalSize(50, 1));
    val tbPass = new TextBox(new TerminalSize(50, 1)).setMask('*');
    val tbInbox = new TextBox(new TerminalSize(50, 1));

    if (existing != null) {
      tbName.setText(nullToEmpty(existing.getName()));
      tbHost.setText(nullToEmpty(existing.getHost()));
      tbPort.setText(nullToEmpty(existing.getPort()));
      tbUser.setText(nullToEmpty(existing.getUsername()));
      tbPass.setText(nullToEmpty(existing.getPassword()));
      tbInbox.setText(nullToEmpty(existing.getInboxFolder()));
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
                      .build();
              val folders = fetchImapFolders(gui, tmp);
              if (folders == null) return;
              val chosen = chooseFromList(gui, "Seleziona cartella", folders, tbInbox.getText());
              if (chosen != null) tbInbox.setText(chosen);
            }));
    root.addComponent(inboxRow);

    val buttons = new Panel(new LinearLayout(Direction.HORIZONTAL));
    val result = new ImapConfig[1];

    buttons.addComponent(
        new Button(
            "Test",
            () -> {
              if (!isPositiveInt(tbPort.getText())) {
                MessageDialog.showMessageDialog(
                    gui,
                    "Errore",
                    "La porta deve essere un intero positivo.",
                    MessageDialogButton.OK);
                return;
              }
              val err =
                  tryTestImap(
                      tbHost.getText(), tbPort.getText(), tbUser.getText(), tbPass.getText());
              MessageDialog.showMessageDialog(
                  gui,
                  err == null ? "OK" : "Errore",
                  err == null ? "Connessione IMAP riuscita." : err,
                  MessageDialogButton.OK);
            }));

    buttons.addComponent(new EmptySpace(new TerminalSize(2, 1)));
    buttons.addComponent(
        new Button(
            "OK",
            () -> {
              val name = tbName.getText().trim();
              val host = tbHost.getText().trim();
              val port = tbPort.getText().trim();
              val user = tbUser.getText().trim();
              val pass = tbPass.getText(); // può essere vuota
              val inbox = tbInbox.getText().trim();

              if (LangUtils.emptyString(name)
                  || LangUtils.emptyString(host)
                  || LangUtils.emptyString(port)
                  || LangUtils.emptyString(user)
                  || LangUtils.emptyString(inbox)) {
                MessageDialog.showMessageDialog(
                    gui,
                    "Errore",
                    "Nome, Host, Porta, Username e Inbox sono obbligatori.",
                    MessageDialogButton.OK);
                return;
              }
              if (!isPositiveInt(port)) {
                MessageDialog.showMessageDialog(
                    gui,
                    "Errore",
                    "La porta deve essere un intero positivo.",
                    MessageDialogButton.OK);
                return;
              }
              // univocità nome
              boolean nameTaken =
                  mailManager.getImaps().stream()
                      .anyMatch(
                          c -> !Objects.equals(existing, c) && Objects.equals(name, c.getName()));
              if (nameTaken) {
                MessageDialog.showMessageDialog(
                    gui, "Errore", "Esiste già un server con questo Nome.", MessageDialogButton.OK);
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

  // ============================  RULES  ============================

  private Panel buildRulesPanel(@NonNull final MultiWindowTextGUI gui) {
    val panel = new Panel(new LinearLayout(Direction.VERTICAL));
    panel.addComponent(new Label("Gestione Regole").addStyle(SGR.BOLD));

    val table = new Table<String>("#", "IMAP", "Soggetto", "Operatore", "Valore", "Azione", "Dest");
    this.rulesTable = table;
    table.setPreferredSize(new TerminalSize(110, 16));
    table.setSelectAction(() -> editSelectedRule(gui));

    refreshRulesTable(table);

    panel.addComponent(table);
    panel.addComponent(new EmptySpace(new TerminalSize(0, 1)));

    val actions = new Panel(new LinearLayout(Direction.HORIZONTAL));
    actions.addComponent(
        new Button(
            "Aggiungi",
            () -> {
              val r = openRuleDialog(gui, null);
              if (r != null) {
                mailManager.getRules().add(r);
                mailManager.saveRules();
                refreshRulesTable(table);
              }
            }));
    actions.addComponent(new EmptySpace(new TerminalSize(1, 1)));
    actions.addComponent(new Button("Modifica", () -> editSelectedRule(gui)));
    actions.addComponent(new EmptySpace(new TerminalSize(1, 1)));
    actions.addComponent(new Button("Elimina", () -> delRule(gui)));

    panel.addComponent(actions);
    return panel;
  }

  private void editSelectedRule(MultiWindowTextGUI gui) {
    if (rulesTable == null) return;
    int sel = rulesTable.getSelectedRow();
    if (sel < 0 || sel >= mailManager.getRules().size()) {
      MessageDialog.showMessageDialog(gui, "Info", "Seleziona una regola.", MessageDialogButton.OK);
      return;
    }
    val updated = openRuleDialog(gui, mailManager.getRules().get(sel));
    if (updated != null) {
      mailManager.getRules().set(sel, updated);
      mailManager.saveRules();
      refreshRulesTable(rulesTable);
      rulesTable.takeFocus();
    }
  }

  private void delRule(@NonNull final MultiWindowTextGUI gui) {
    if (mailManager.getRules().isEmpty()) {
      MessageDialog.showMessageDialog(
          gui, "Info", "Nessuna regola da eliminare.", MessageDialogButton.OK);
      return;
    }
    int sel = rulesTable.getSelectedRow();
    if (sel < 0 || sel >= mailManager.getRules().size()) {
      MessageDialog.showMessageDialog(gui, "Info", "Seleziona una regola.", MessageDialogButton.OK);
      return;
    }
    val resp =
        MessageDialog.showMessageDialog(
            gui,
            "Conferma",
            "Eliminare la regola selezionata?",
            MessageDialogButton.Yes,
            MessageDialogButton.No);
    if (resp == MessageDialogButton.Yes) {
      mailManager.getRules().remove(sel);
      mailManager.saveRules();
      refreshRulesTable(rulesTable);
    }
  }

  private void refreshRulesTable(Table<String> table) {
    int prev = table.getSelectedRow();
    table.getTableModel().clear();
    val rules = mailManager.getRules();
    for (int i = 0; i < rules.size(); i++) {
      val r = rules.get(i);
      table
          .getTableModel()
          .addRow(
              String.valueOf(i + 1),
              nullToEmpty(r.getImapConfigName()),
              String.valueOf(r.getConditionSubject()),
              String.valueOf(r.getConditionOperator()),
              nullToEmpty(r.getConditionValue()),
              String.valueOf(r.getActionType()),
              nullToEmpty(r.getDestValue()));
    }
    if (!rules.isEmpty()) {
      int newSel = Math.max(0, Math.min(prev, rules.size() - 1));
      table.setSelectedRow(newSel);
    }
  }

  private Rule openRuleDialog(@NonNull final MultiWindowTextGUI gui, final Rule existing) {
    val dialog = new BasicWindow(existing == null ? "Aggiungi Regola" : "Modifica Regola");
    dialog.setHints(List.of(Window.Hint.CENTERED, Window.Hint.MODAL));

    val root = new Panel(new GridLayout(2));
    root.setPreferredSize(new TerminalSize(100, 14));

    // IMAP name combo
    val imapNames = new ArrayList<String>();
    for (ImapConfig c : mailManager.getImaps()) imapNames.add(c.getName());
    val cbImap = new ComboBox<>(imapNames);

    val cbSubject = new ComboBox<>(ConditionSubject.values());
    val cbOperator = new ComboBox<>(ConditionOperator.values());
    val tbValue = new TextBox(new TerminalSize(60, 1));
    val cbAction = new ComboBox<>(ActionType.values());
    val tbDest = new TextBox(new TerminalSize(60, 1));

    val btnPickFolder =
        new Button(
            "Sfoglia cartelle…",
            () -> {
              String chosenImap = cbImap.getSelectedItem();
              ImapConfig cfg =
                  mailManager.getImaps().stream()
                      .filter(c -> Objects.equals(c.getName(), chosenImap))
                      .findFirst()
                      .orElse(null);
              if (cfg == null) {
                MessageDialog.showMessageDialog(
                    gui, "Info", "Seleziona prima un server IMAP.", MessageDialogButton.OK);
                return;
              }
              val folders = fetchImapFolders(gui, cfg);
              if (folders == null) return;
              val chosen = chooseFromList(gui, "Seleziona cartella", folders, tbDest.getText());
              if (chosen != null) tbDest.setText(chosen);
            });

    if (existing != null) {
      cbImap.setSelectedItem(existing.getImapConfigName());
      cbSubject.setSelectedItem(existing.getConditionSubject());
      cbOperator.setSelectedItem(existing.getConditionOperator());
      tbValue.setText(nullToEmpty(existing.getConditionValue()));
      cbAction.setSelectedItem(existing.getActionType());
      tbDest.setText(nullToEmpty(existing.getDestValue()));
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
    final Rule[] result = new Rule[1];
    buttons.addComponent(
        new Button(
            "OK",
            () -> {
              String imapName = (String) cbImap.getSelectedItem();
              if (imapName == null || imapName.isBlank()) {
                MessageDialog.showMessageDialog(
                    gui, "Errore", "Seleziona un server IMAP.", MessageDialogButton.OK);
                return;
              }
              if (tbValue.getText().isBlank()) {
                MessageDialog.showMessageDialog(
                    gui,
                    "Errore",
                    "Il valore della condizione è obbligatorio.",
                    MessageDialogButton.OK);
                return;
              }
              val action = cbAction.getSelectedItem();
              val dest = tbDest.getText().trim();
              if (action != ActionType.DELETE && dest.isBlank()) {
                MessageDialog.showMessageDialog(
                    gui,
                    "Errore",
                    "La destinazione è obbligatoria per l'azione selezionata.",
                    MessageDialogButton.OK);
                return;
              }
              result[0] =
                  Rule.builder()
                      .imapConfigName(imapName)
                      .conditionSubject(cbSubject.getSelectedItem())
                      .conditionOperator(cbOperator.getSelectedItem())
                      .conditionValue(tbValue.getText().trim())
                      .actionType(action)
                      .destValue(action == ActionType.DELETE ? null : dest)
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

  // ============================  COMMON  ============================

  private List<String> fetchImapFolders(MultiWindowTextGUI gui, ImapConfig cfg) {
    try {
      Properties props = cfg.toProperties();
      Session sess = Session.getInstance(props);
      try (Store store = sess.getStore("imaps")) {
        store.connect(cfg.getHost(), cfg.getUsername(), cfg.getPassword());
        List<String> out = new ArrayList<>();
        Folder def = store.getDefaultFolder();
        for (Folder f : def.list("*")) {
          if ((f.getType() & Folder.HOLDS_MESSAGES) != 0) out.add(f.getFullName());
        }
        if (out.isEmpty()) {
          MessageDialog.showMessageDialog(
              gui, "Info", "Nessuna cartella messaggi trovata.", MessageDialogButton.OK);
        }
        return out;
      }
    } catch (MessagingException ex) {
      MessageDialog.showMessageDialog(gui, "Errore IMAP", rootCauseMsg(ex), MessageDialogButton.OK);
      return null;
    }
  }

  private String chooseFromList(
      MultiWindowTextGUI gui, String title, List<String> items, String preselect) {
    val dialog = new BasicWindow(title);
    dialog.setHints(List.of(Window.Hint.CENTERED, Window.Hint.MODAL));
    dialog.setCloseWindowWithEscape(true);

    val table = new Table<>("Elementi");
    table.setPreferredSize(new TerminalSize(70, 16));
    for (val it : items) table.getTableModel().addRow(it);
    int preIndex = preselect == null ? -1 : items.indexOf(preselect);
    if (preIndex >= 0) table.setSelectedRow(preIndex);

    val result = new String[1];
    val ok =
        new Button(
            "OK",
            () -> {
              val sel = table.getSelectedRow();
              if (sel >= 0) result[0] = items.get(sel);
              dialog.close();
            });
    val cancel = new Button("Annulla", dialog::close);
    table.setSelectAction(ok::takeFocus);

    val root = new Panel(new LinearLayout(Direction.VERTICAL));
    root.addComponent(table);
    val actions = new Panel(new LinearLayout(Direction.HORIZONTAL));
    actions.addComponent(ok);
    actions.addComponent(new EmptySpace(new TerminalSize(1, 1)));
    actions.addComponent(cancel);
    root.addComponent(new EmptySpace(new TerminalSize(0, 1)));
    root.addComponent(actions);

    dialog.setComponent(root);
    gui.addWindowAndWait(dialog);
    return result[0];
  }

  private boolean isPositiveInt(String s) {
    try {
      return Integer.parseInt(s) > 0;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  private String nullToEmpty(String s) {
    return s == null ? "" : s;
  }

  private String rootCauseMsg(@NonNull Throwable t) {
    while (t.getCause() != null) t = t.getCause();
    return t.getMessage() == null ? t.toString() : t.getMessage();
  }

  private String tryTestImap(
      @NonNull String host, @NonNull String port, @NonNull String user, @NonNull String pass) {
    try {
      val cfg =
          ImapConfig.builder()
              .name("test")
              .host(host)
              .port(port)
              .username(user)
              .password(pass)
              .inboxFolder("INBOX")
              .build();
      val props = cfg.toProperties();
      val sess = Session.getInstance(props);
      try (val store = sess.getStore("imaps")) {
        store.connect(cfg.getHost(), cfg.getUsername(), cfg.getPassword());
      }
      return null;
    } catch (Exception ex) {
      return rootCauseMsg(ex);
    }
  }

  // ============================  SHELL  ============================

  public void start() throws IOException {
    val terminalFactory = new DefaultTerminalFactory();
    val screen = terminalFactory.createScreen();
    try {
      screen.startScreen();
      val gui =
          new MultiWindowTextGUI(
              screen, new DefaultWindowManager(), new EmptySpace(TextColor.ANSI.BLACK));

      val window = new BasicWindow("MailManager • Server IMAP & Regole");
      window.setHints(List.of(Window.Hint.CENTERED));

      val root = new Panel();
      root.setLayoutManager(new LinearLayout(Direction.VERTICAL));
      root.setPreferredSize(new TerminalSize(110, 30));

      // Navbar
      val nav = new Panel(new LinearLayout(Direction.HORIZONTAL));
      val tabImaps = new Button("[ IMAPs ]", () -> switchTo(gui, root, content, ActiveTab.IMAPS));
      val tabRules = new Button("[ Regole ]", () -> switchTo(gui, root, content, ActiveTab.RULES));
      nav.addComponent(tabImaps);
      nav.addComponent(new EmptySpace(new TerminalSize(1, 1)));
      nav.addComponent(tabRules);

      // Content
      content = new Panel(new LinearLayout(Direction.VERTICAL));
      content.addComponent(buildImapsPanel(gui)); // default

      // Footer
      val footer = new Panel(new LinearLayout(Direction.HORIZONTAL));
      footer.addComponent(
          new EmptySpace(), LinearLayout.createLayoutData(LinearLayout.Alignment.Fill));
      footerHint = new Label("").addStyle(SGR.BOLD);
      footer.addComponent(footerHint);

      root.addComponent(nav);
      root.addComponent(new EmptySpace(new TerminalSize(0, 1)));
      root.addComponent(content, LinearLayout.createLayoutData(LinearLayout.Alignment.Fill));
      root.addComponent(new EmptySpace(new TerminalSize(0, 1)));
      root.addComponent(footer);

      window.setComponent(root);
      window.setCloseWindowWithEscape(true);

      // Hotkeys
      window.addWindowListener(
          new WindowListenerAdapter() {
            @Override
            public void onUnhandledInput(Window basePane, KeyStroke key, AtomicBoolean handled) {
              switch (key.getKeyType()) {
                case F1 -> {
                  switchTo(gui, root, content, ActiveTab.IMAPS);
                  handled.set(true);
                }
                case F2 -> {
                  switchTo(gui, root, content, ActiveTab.RULES);
                  handled.set(true);
                }
                case F8 -> {
                  basePane.close();
                  handled.set(true);
                }
                case Enter -> {
                  if (tab == ActiveTab.IMAPS) editSelectedImap(gui);
                  else if (tab == ActiveTab.RULES) editSelectedRule(gui);
                  handled.set(true);
                }
                case Insert -> {
                  if (tab == ActiveTab.IMAPS) {
                    val cfg = openImapDialog(gui, null);
                    if (cfg != null) {
                      mailManager.getImaps().add(cfg);
                      mailManager.saveImaps();
                      refreshImapsTable(imapsTable);
                      if (imapsTable != null) imapsTable.takeFocus();
                    }
                  } else if (tab == ActiveTab.RULES) {
                    val r = openRuleDialog(gui, null);
                    if (r != null) {
                      mailManager.getRules().add(r);
                      mailManager.saveRules();
                      refreshRulesTable(rulesTable);
                      if (rulesTable != null) rulesTable.takeFocus();
                    }
                  }
                  handled.set(true);
                }
                case Delete -> {
                  if (tab == ActiveTab.IMAPS) delSelectedImap(gui);
                  else if (tab == ActiveTab.RULES) delRule(gui);
                  handled.set(true);
                }
                default -> {
                  /* no-op */
                }
              }
            }
          });

      // set footer text & focus
      switchTo(gui, root, content, ActiveTab.IMAPS);

      gui.addWindowAndWait(window);
    } finally {
      screen.stopScreen();
    }
  }

  private void switchTo(
      @NonNull final MultiWindowTextGUI gui,
      @NonNull final Panel root,
      @NonNull final Panel container,
      @NonNull final ActiveTab tab) {
    this.tab = tab;
    final Panel newContent = (tab == ActiveTab.IMAPS) ? buildImapsPanel(gui) : buildRulesPanel(gui);
    container.removeAllComponents();
    container.addComponent(newContent, LinearLayout.createLayoutData(LinearLayout.Alignment.Fill));

    footerHint.setText(
        tab == ActiveTab.RULES
            ? "F1: IMAPs  |  F2: Regole  |  ENTER: Modifica  |  INS: Aggiungi  |  DEL: Elimina  | "
                + " F8: Esci"
            : "F1: IMAPs  |  F2: Regole  |  ENTER: Modifica  |  INS: Aggiungi  |  DEL: Elimina  | "
                + " F8: Esci");

    if (tab == ActiveTab.RULES && rulesTable != null) rulesTable.takeFocus();
    if (tab == ActiveTab.IMAPS && imapsTable != null) imapsTable.takeFocus();
  }
}
