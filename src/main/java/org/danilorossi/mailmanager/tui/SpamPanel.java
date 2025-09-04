package org.danilorossi.mailmanager.tui;

import com.googlecode.lanterna.gui2.Button;
import com.googlecode.lanterna.gui2.CheckBox;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.EmptySpace;
import com.googlecode.lanterna.gui2.GridLayout;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.TextBox;
import lombok.NonNull;
import lombok.val;
import org.danilorossi.mailmanager.MailManager;
import org.danilorossi.mailmanager.helpers.LangUtils;

public class SpamPanel extends Panel {

  private final MultiWindowTextGUI gui;
  private final MailManager mailManager;

  public SpamPanel(@NonNull final MultiWindowTextGUI gui, @NonNull final MailManager mailManager) {
    this.gui = gui;
    this.mailManager = mailManager;
  }

  public SpamPanel build() {
    setLayoutManager(new LinearLayout(Direction.VERTICAL));

    val cfg = mailManager.getSpamConfig();

    // --------- Content (grid 2 colonne) ---------
    val content = new Panel(new GridLayout(2));

    // Widgets
    val enabledCB = new CheckBox("Enable SpamAssassin");
    enabledCB.setChecked(cfg.isEnabled());
    content.addComponent(enabledCB, GridLayout.createHorizontallyFilledLayoutData(2));

    val hostLabel = new Label("Host:");
    final TextBox hostTB = TuiUtils.requiredTextBox(40, LangUtils.nullToEmpty(cfg.getHost()));
    content.addComponent(hostLabel);
    content.addComponent(hostTB);

    val portLabel = new Label("Port:");
    final TextBox portTB = TuiUtils.portBox(Integer.toString(cfg.getPort()));
    content.addComponent(portLabel);
    content.addComponent(portTB);

    val userLabel = new Label("User (optional):");
    final TextBox userTB = new TextBox().setText(cfg.getUser() == null ? "" : cfg.getUser());
    content.addComponent(userLabel);
    content.addComponent(userTB);

    val connTO = new Label("Connect timeout (ms):");
    final TextBox connTOTB = TuiUtils.timeoutBox(Integer.toString(cfg.getConnectTimeoutMillis()));
    content.addComponent(connTO);
    content.addComponent(connTOTB);

    val readTO = new Label("Read timeout (ms):");
    final TextBox readTOTB = TuiUtils.timeoutBox(Integer.toString(cfg.getReadTimeoutMillis()));
    content.addComponent(readTO);
    content.addComponent(readTOTB);

    addComponent(content);

    // --------- Buttons ---------
    val buttons = new Panel(new GridLayout(2));
    val saveBtn =
        new Button(
            "Save",
            () -> {
              // Validazioni uniformi
              if (!TuiUtils.validateRequired(gui, "Host", hostTB)) return;
              if (!TuiUtils.validateRequired(gui, "Port", portTB)) return;
              if (!TuiUtils.validatePort(gui, portTB)) return;

              if (!TuiUtils.validateIntRange(gui, "Connect timeout (ms)", connTOTB, 100, 120_000))
                return;
              if (!TuiUtils.validateIntRange(gui, "Read timeout (ms)", readTOTB, 100, 120_000))
                return;

              try {
                final String host = hostTB.getText().trim();
                final int port = LangUtils.parseIntOr(portTB.getText(), 783);
                final int cto = LangUtils.parseIntOr(connTOTB.getText(), 3000);
                final int rto = LangUtils.parseIntOr(readTOTB.getText(), 5000);
                final String user = userTB.getText().trim();

                cfg.setEnabled(enabledCB.isChecked())
                    .setHost(host)
                    .setPort(port)
                    .setUser(user.isEmpty() ? null : user)
                    .setConnectTimeoutMillis(cto)
                    .setReadTimeoutMillis(rto);

                mailManager.saveSpamConfig();
                TuiUtils.info(gui, "SpamAssassin configuration saved.");
              } catch (Exception ex) {
                TuiUtils.error(gui, "Failed to save: " + LangUtils.rootCauseMsg(ex));
              }
            });

    buttons.addComponent(saveBtn);

    addComponent(new EmptySpace(), GridLayout.createHorizontallyFilledLayoutData(2));
    addComponent(buttons, GridLayout.createHorizontallyFilledLayoutData(2));

    return this;
  }
}
