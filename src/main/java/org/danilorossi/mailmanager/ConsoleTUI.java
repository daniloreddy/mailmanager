package org.danilorossi.mailmanager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.mail.Folder;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.Store;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.Button;
import com.googlecode.lanterna.gui2.ComboBox;
import com.googlecode.lanterna.gui2.DefaultWindowManager;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.EmptySpace;
import com.googlecode.lanterna.gui2.GridLayout;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.TextBox;
import com.googlecode.lanterna.gui2.Window;
import com.googlecode.lanterna.gui2.WindowListenerAdapter;
import com.googlecode.lanterna.gui2.dialogs.MessageDialog;
import com.googlecode.lanterna.gui2.dialogs.MessageDialogButton;
import com.googlecode.lanterna.gui2.table.Table;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;

import lombok.NonNull;
import lombok.val;

public class ConsoleTUI {

  enum ActiveTab {
    IMAP,
    RULES
  }

  private Panel content;
  private final MailManager mailManager;
  private Table<String> rulesTable;
  private ActiveTab tab;
  private Label footerHint;

  public ConsoleTUI(@NonNull MailManager mailManager) {
    this.mailManager = mailManager;
  }

  private Panel buildImapPanel(@NonNull final MultiWindowTextGUI gui) {
    val cfg = mailManager.getImapConfig();

    val panel = new Panel();
    panel.setLayoutManager(new LinearLayout(Direction.VERTICAL));

    panel.addComponent(new Label("Configurazione Server IMAP").addStyle(SGR.BOLD));

    val form = new Panel(new GridLayout(2));
    form.setPreferredSize(new TerminalSize(90, 12));

    val tbHost = new TextBox(new TerminalSize(50, 1), cfg.getHost());
    val tbPort = new TextBox(new TerminalSize(10, 1), cfg.getPort());
    val tbUser = new TextBox(new TerminalSize(50, 1), cfg.getUsername());
    val tbPass = new TextBox(new TerminalSize(50, 1), cfg.getPassword()).setMask('*');
    val tbInbox = new TextBox(new TerminalSize(50, 1), cfg.getInboxFolder());

    form.addComponent(new Label("Host:"));
    form.addComponent(tbHost);
    form.addComponent(new Label("Porta:"));
    form.addComponent(tbPort);
    form.addComponent(new Label("Username:"));
    form.addComponent(tbUser);
    form.addComponent(new Label("Password:"));
    form.addComponent(tbPass);

    form.addComponent(new Label("Cartella Inbox:"));
    val inboxRow = new Panel(new LinearLayout(Direction.HORIZONTAL));
    inboxRow.addComponent(tbInbox);
    inboxRow.addComponent(new EmptySpace(new TerminalSize(1, 1)));
    inboxRow.addComponent(
        new Button(
            "Sfoglia…",
            () -> {
              val folders = fetchImapFolders(gui);
              if (folders == null) return;
              val chosen = chooseFromList(gui, "Seleziona cartella", folders, tbInbox.getText());
              if (chosen != null) tbInbox.setText(chosen);
            }));
    form.addComponent(inboxRow);

    panel.addComponent(form);
    panel.addComponent(new EmptySpace(new TerminalSize(0, 1)));

    val actions = new Panel(new LinearLayout(Direction.HORIZONTAL));
    actions.addComponent(
        new Button(
            "Test connessione",
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
              if (err == null) {
                MessageDialog.showMessageDialog(
                    gui, "OK", "Connessione IMAP riuscita.", MessageDialogButton.OK);
              } else {
                MessageDialog.showMessageDialog(gui, "Errore", err, MessageDialogButton.OK);
              }
            }));
    actions.addComponent(new EmptySpace(new TerminalSize(2, 1)));
    actions.addComponent(
        new Button(
            "Salva configurazione",
            () -> {
              if (!isPositiveInt(tbPort.getText())) {
                MessageDialog.showMessageDialog(
                    gui,
                    "Errore",
                    "La porta deve essere un intero positivo.",
                    MessageDialogButton.OK);
                return;
              }
              if (tbHost.getText().isBlank()
                  || tbPort.getText().isBlank()
                  || tbUser.getText().isBlank()
                  || tbInbox.getText().isBlank()) {
                MessageDialog.showMessageDialog(
                    gui,
                    "Errore",
                    "Host, Porta, Username e Inbox sono obbligatori.",
                    MessageDialogButton.OK);
                return;
              }
              val newCfg =
                  ImapConfig.builder()
                      .host(tbHost.getText().trim())
                      .port(tbPort.getText().trim())
                      .username(tbUser.getText().trim())
                      .password(tbPass.getText()) // vuota consentita se usi MAIL_PASSWORD
                      .inboxFolder(tbInbox.getText().trim())
                      .build();
              mailManager.saveImapConfig(newCfg);
              MessageDialog.showMessageDialog(
                  gui, "Salvato", "Configurazione IMAP salvata.", MessageDialogButton.OK);
            }));
    panel.addComponent(actions);

    tbHost.takeFocus(); // <— focus iniziale
    return panel;
  }

  private Panel buildRulesPanel(@NonNull final MultiWindowTextGUI gui) {
    val panel = new Panel(new LinearLayout(Direction.VERTICAL));
    panel.addComponent(new Label("Gestione Regole").addStyle(SGR.BOLD));

    // Tabella regole
    val table = new Table<String>("#", "Soggetto", "Operatore", "Valore", "Azione", "Dest");
    this.rulesTable = table;
    table.setPreferredSize(new TerminalSize(96, 16));

    table.setSelectAction(
        () -> {
          val sel = table.getSelectedRow();
          if (sel >= 0 && sel < mailManager.getRules().size()) {
            val updated = openRuleDialog(gui, mailManager.getRules().get(sel));
            if (updated != null) {
              mailManager.getRules().set(sel, updated);
              mailManager.saveRules();
              refreshRulesTable(table);
            }
          }
        });

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
    actions.addComponent(
        new Button(
            "Modifica",
            () -> {
              val sel = table.getSelectedRow();
              if (sel < 0 || sel >= mailManager.getRules().size()) {
                MessageDialog.showMessageDialog(
                    gui, "Info", "Seleziona una regola.", MessageDialogButton.OK);
                return;
              }
              val updated = openRuleDialog(gui, mailManager.getRules().get(sel));
              if (updated != null) {
                mailManager.getRules().set(sel, updated);
                mailManager.saveRules();
                refreshRulesTable(table);
              }
            }));
    actions.addComponent(new EmptySpace(new TerminalSize(1, 1)));
    actions.addComponent(new Button("Elimina", () -> delRule(gui)));
    panel.addComponent(actions);

    return panel;
  }

  private String chooseFromList(
      MultiWindowTextGUI gui, String title, List<String> items, String preselect) {
    val dialog = new BasicWindow(title);
    dialog.setHints(List.of(Window.Hint.CENTERED, Window.Hint.MODAL));
    dialog.setCloseWindowWithEscape(true); // <— ESC chiude

    val table = new Table<>("Cartelle");
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

  private void delRule(@NonNull final MultiWindowTextGUI gui) {

    if (mailManager.getRules().isEmpty()) {
      MessageDialog.showMessageDialog(
          gui, "Info", "Nessuna regola da eliminare.", MessageDialogButton.OK);
      return;
    }

    val sel = rulesTable.getSelectedRow();
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

  private List<String> fetchImapFolders(MultiWindowTextGUI gui) {
    try {
      ImapConfig cfg = mailManager.getImapConfig();
      Properties props = cfg.toProperties();
      Session sess = Session.getInstance(props);
      try (Store store = sess.getStore("imaps")) {
        store.connect(cfg.getHost(), cfg.getUsername(), cfg.getPassword());
        List<String> out = new ArrayList<>();
        Folder def = store.getDefaultFolder();
        for (Folder f : def.list("*")) {
          if ((f.getType() & Folder.HOLDS_MESSAGES) != 0) {
            out.add(f.getFullName());
          }
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

  private boolean isPositiveInt(final String s) {
    try {
      return Integer.parseInt(s) > 0;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  private String nullToEmpty(String s) {
    return s == null ? "" : s;
  }

  private Rule openRuleDialog(@NonNull final MultiWindowTextGUI gui, final Rule existing) {
    val dialog = new BasicWindow(existing == null ? "Aggiungi Regola" : "Modifica Regola");
    dialog.setHints(List.of(Window.Hint.CENTERED, Window.Hint.MODAL));

    val root = new Panel(new GridLayout(2));
    root.setPreferredSize(new TerminalSize(80, 12));

    val cbSubject = new ComboBox<>(ConditionSubject.values());
    val cbOperator = new ComboBox<>(ConditionOperator.values());
    val tbValue = new TextBox(new TerminalSize(50, 1));
    val cbAction = new ComboBox<>(ActionType.values());
    val tbDest = new TextBox(new TerminalSize(50, 1));

    val btnPickFolder =
        new Button(
            "Sfoglia cartelle…",
            () -> {
              val folders = fetchImapFolders(gui);
              if (folders == null) return;
              val chosen = chooseFromList(gui, "Seleziona cartella", folders, tbDest.getText());
              if (chosen != null) tbDest.setText(chosen);
            });

    if (existing != null) {
      cbSubject.setSelectedItem(existing.getConditionSubject());
      cbOperator.setSelectedItem(existing.getConditionOperator());
      tbValue.setText(nullToEmpty(existing.getConditionValue()));
      cbAction.setSelectedItem(existing.getActionType());
      tbDest.setText(nullToEmpty(existing.getDestValue()));
    } else {
      cbSubject.setSelectedIndex(ConditionSubject.FROM.ordinal());
      cbOperator.setSelectedIndex(ConditionOperator.CONTAINS.ordinal());
      cbAction.setSelectedIndex(ActionType.MOVE.ordinal());
    }

    // Abilita/disabilita destinazione
    final Runnable refreshDestEnabled =
        () -> {
          boolean needDest = cbAction.getSelectedItem() != ActionType.DELETE;
          tbDest.setEnabled(needDest);
          btnPickFolder.setEnabled(needDest);
        };
    cbAction.addListener(
        (selectedIndex, previousSelection, changedByUserInteraction) -> refreshDestEnabled.run());
    refreshDestEnabled.run();

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
    gui.addWindowAndWait(dialog);
    return result[0];
  }

  private void refreshRulesTable(Table<String> table) {
    val prev = table.getSelectedRow();
    table.getTableModel().clear();

    List<Rule> rules = mailManager.getRules();
    for (int i = 0; i < rules.size(); i++) {
      val r = rules.get(i);
      table
          .getTableModel()
          .addRow(
              String.valueOf(i + 1),
              String.valueOf(r.getConditionSubject()),
              String.valueOf(r.getConditionOperator()),
              nullToEmpty(r.getConditionValue()),
              String.valueOf(r.getActionType()),
              nullToEmpty(r.getDestValue()));
    }
    if (!rules.isEmpty()) {
      val newSel = Math.max(0, Math.min(prev, rules.size() - 1));
      table.setSelectedRow(newSel);
    }
  }

  private String rootCauseMsg(@NonNull Throwable t) {
    while (t.getCause() != null) t = t.getCause();
    return t.getMessage() == null ? t.toString() : t.getMessage();
  }

  // Avvio UI
  public void start() throws IOException {
    val terminalFactory = new DefaultTerminalFactory();
    val screen = terminalFactory.createScreen();
    try {
      screen.startScreen();

      val gui =
          new MultiWindowTextGUI(
              screen, new DefaultWindowManager(), new EmptySpace(TextColor.ANSI.BLACK));

      val window = new BasicWindow("MailManager • Configurazione IMAP & Regole");
      window.setHints(List.of(Window.Hint.CENTERED));

      val root = new Panel();
      root.setLayoutManager(new LinearLayout(Direction.VERTICAL));
      root.setPreferredSize(new TerminalSize(100, 28));

      // Navbar “finta” per cambiare pannello
      val nav = new Panel(new LinearLayout(Direction.HORIZONTAL));
      val tabImap = new Button("[ IMAP ]", () -> switchTo(gui, root, content, ActiveTab.IMAP));
      val tabRules = new Button("[ Regole ]", () -> switchTo(gui, root, content, ActiveTab.RULES));
      nav.addComponent(tabImap);
      nav.addComponent(new EmptySpace(new TerminalSize(1, 1)));
      nav.addComponent(tabRules);

      // Contenitore centrale (iniziale) che sostituiamo
      content = new Panel();
      content.setLayoutManager(new LinearLayout(Direction.VERTICAL));
      content.addComponent(buildImapPanel(gui)); // default

      // Footer
      val footer = new Panel(new LinearLayout(Direction.HORIZONTAL));
      footer.addComponent(
          new EmptySpace(), LinearLayout.createLayoutData(LinearLayout.Alignment.Fill));
      footerHint = new Label("F1: IMAP  |  F2: Regole  |  F8: Esci").addStyle(SGR.BOLD);
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
            public void onUnhandledInput(
                final Window basePane, final KeyStroke key, final AtomicBoolean hasBeenHandled) {
              val type = key.getKeyType();
              switch (type) {
                case F1 -> {
                  switchTo(gui, root, content, ActiveTab.IMAP);
                  hasBeenHandled.set(true);
                }
                case F2 -> {
                  switchTo(gui, root, content, ActiveTab.RULES);
                  hasBeenHandled.set(true);
                }
                case F8 -> {
                  basePane.close();
                  hasBeenHandled.set(true);
                }
                case Enter -> {
                  if (tab != ActiveTab.RULES || rulesTable == null) return;
                  val sel = rulesTable.getSelectedRow();
                  if (sel >= 0 && sel < mailManager.getRules().size()) {
                    val updated = openRuleDialog(gui, mailManager.getRules().get(sel));
                    if (updated != null) {
                      mailManager.getRules().set(sel, updated);
                      mailManager.saveRules();
                      refreshRulesTable(rulesTable);
                      rulesTable.takeFocus();
                    }
                  }
                  hasBeenHandled.set(true);
                }
                case Insert -> {
                  if (tab != ActiveTab.RULES) {
                    switchTo(gui, root, content, ActiveTab.RULES);
                  }
                  val r = openRuleDialog(gui, null);
                  if (r != null) {
                    mailManager.getRules().add(r);
                    mailManager.saveRules();
                    refreshRulesTable(rulesTable);
                    if (rulesTable != null) rulesTable.takeFocus();
                  }
                  hasBeenHandled.set(true);
                }
                case Delete -> {
                  if (tab == ActiveTab.RULES) delRule(gui);
                  hasBeenHandled.set(true);
                }
                default -> {
                  /* no-op */
                }
              }
            }
          });

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

    final Panel newContent;
    switch (tab) {
      case IMAP:
        newContent = buildImapPanel(gui);
        break;
      case RULES:
        newContent = buildRulesPanel(gui);
        break;
      default:
        throw new IllegalArgumentException("Unexpected value: " + tab);
    }

    container.removeAllComponents();
    container.addComponent(newContent, LinearLayout.createLayoutData(LinearLayout.Alignment.Fill));

    footerHint.setText(
        tab == ActiveTab.RULES
            ? "F1: IMAP  |  F2: Regole  |  ENTER: Modifica  |  INS: Aggiungi  |  DEL: Elimina  |  F8: Esci"
            : "F1: IMAP  |  F2: Regole  |  F8: Esci");

    if (this.tab == ActiveTab.RULES && this.rulesTable != null) this.rulesTable.takeFocus();
  }

  private String tryTestImap(
      @NonNull final String host,
      @NonNull final String port,
      @NonNull final String user,
      @NonNull final String pass) {
    try {
      val cfg =
          ImapConfig.builder()
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
}
