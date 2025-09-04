package org.danilorossi.mailmanager.tui;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.*;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.NonNull;
import lombok.extern.java.Log;
import lombok.val;
import org.danilorossi.mailmanager.MailManager;
import org.danilorossi.mailmanager.helpers.LogConfigurator;

@Log
public class ConsoleTUI {

  static {
    LogConfigurator.configLog(log);
    // LogConfigurator.redirectSystemStreams(LOG);
  }

  enum ActiveTab {
    IMAPS,
    RULES,
    SPAMASSASSIN
  }

  private ImapPanel imapPanel;
  private RulesPanel rulesPanel;
  private SpamPanel spamPanel;

  private MultiWindowTextGUI gui;
  private MailManager mailManager;

  private Panel content;
  private Label footerHint;
  private ActiveTab tab = ActiveTab.IMAPS;

  public ConsoleTUI(@NonNull MailManager mailManager) {
    this.mailManager = mailManager;
  }

  private ImapPanel getImapsPanel() {
    if (imapPanel == null) imapPanel = new ImapPanel(gui, mailManager).build();
    return imapPanel;
  }

  private RulesPanel getRulesPanel() {
    if (rulesPanel == null) rulesPanel = new RulesPanel(gui, mailManager).build();
    return rulesPanel;
  }

  private SpamPanel getSpamPanel() {
    if (spamPanel == null) spamPanel = new SpamPanel(gui, mailManager).build();
    return spamPanel;
  }

  public void start() throws IOException {

    val terminalFactory = new DefaultTerminalFactory();
    val screen = terminalFactory.createScreen();
    try {
      screen.startScreen();
      gui =
          new MultiWindowTextGUI(
              screen, new DefaultWindowManager(), new EmptySpace(TextColor.ANSI.BLACK));

      val window = new BasicWindow("MailManager | IMAP | Regole | SpamAssassin");

      window.setHints(List.of(Window.Hint.CENTERED));

      val root = new Panel();
      root.setLayoutManager(new LinearLayout(Direction.VERTICAL));
      root.setPreferredSize(new TerminalSize(110, 30));

      // Navbar
      val nav = new Panel(new LinearLayout(Direction.HORIZONTAL));
      val tabImaps = new Button("[ IMAPs ]", () -> switchTo(gui, root, content, ActiveTab.IMAPS));
      val tabRules = new Button("[ Regole ]", () -> switchTo(gui, root, content, ActiveTab.RULES));
      val tabSA =
          new Button(
              "[ SpamAssassin ]", () -> switchTo(gui, root, content, ActiveTab.SPAMASSASSIN));

      nav.addComponent(tabImaps);
      nav.addComponent(new EmptySpace(new TerminalSize(1, 1)));
      nav.addComponent(tabRules);
      nav.addComponent(new EmptySpace(new TerminalSize(1, 1)));
      nav.addComponent(tabSA);

      // Content
      content = new Panel(new LinearLayout(Direction.VERTICAL));
      content.addComponent(getImapsPanel()); // default

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
                case F3 -> {
                  switchTo(gui, root, content, ActiveTab.SPAMASSASSIN);
                  handled.set(true);
                }
                case F8 -> {
                  basePane.close();
                  handled.set(true);
                }
                case Enter -> {
                  if (tab == ActiveTab.IMAPS) imapPanel.editSelectedItem();
                  else if (tab == ActiveTab.RULES) rulesPanel.editSelectedItem();
                  handled.set(true);
                }
                case Insert -> {
                  if (tab == ActiveTab.IMAPS) {
                    val cfg = imapPanel.openItemDialog(null);
                    if (cfg != null) {
                      mailManager.getImaps().add(cfg);
                      mailManager.saveImaps();
                      imapPanel.refreshTable();
                    }
                  } else if (tab == ActiveTab.RULES) {
                    if (mailManager.getImaps().isEmpty()) {
                      TuiUtils.info(gui, "Configura almeno un server IMAP prima di creare regole.");
                    } else {
                      val r = rulesPanel.openItemDialog(null);
                      if (r != null) {
                        mailManager.getRules().add(r);
                        mailManager.saveRules();
                        rulesPanel.refreshTable();
                      }
                    }
                  }
                  handled.set(true);
                }
                case Delete -> {
                  if (tab == ActiveTab.IMAPS) imapPanel.delSelectedItem();
                  else if (tab == ActiveTab.RULES) rulesPanel.delSelectedItem();
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

    final Panel
        newContent; // = (tab == ActiveTab.IMAPS) ? buildImapsPanel(gui) : buildRulesPanel(gui);
    switch (tab) {
      case IMAPS -> newContent = getImapsPanel();
      case RULES -> newContent = getRulesPanel();
      case SPAMASSASSIN -> newContent = getSpamPanel();
      default -> throw new IllegalStateException("Unexpected value: " + tab);
    }
    container.removeAllComponents();
    container.addComponent(newContent, LinearLayout.createLayoutData(LinearLayout.Alignment.Fill));

    footerHint.setText(
        "F1:IMAPs | F2:Regole | F3: Spam | ENTER: Mod. | INS: Agg. | DEL: Elim. | F8: Esci");

    if (tab == ActiveTab.RULES) rulesPanel.focus();
    if (tab == ActiveTab.IMAPS) imapPanel.focus();
  }
}
