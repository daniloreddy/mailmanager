package org.danilorossi.mailmanager.tui;

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
import com.googlecode.lanterna.gui2.TextBox;
import com.googlecode.lanterna.gui2.Window;
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

public class RuleDialog extends BasicWindow {

  private Rule rule = null;
  private final MultiWindowTextGUI gui;
  private final MailManager mailManager;

  private ComboBox<String> cbImap = null;
  private ComboBox<ConditionSubject> cbSubject = null;
  private ComboBox<ConditionOperator> cbOperator = null;
  private TextBox tbValue = null;
  private ComboBox<ActionType> cbAction = null;
  private TextBox tbDest = null;
  private Button btnPickFolder = null;

  public RuleDialog(
      @NonNull final MultiWindowTextGUI gui,
      @NonNull final MailManager mailManager,
      final Rule rule) {
    this.gui = gui;
    this.mailManager = mailManager;
    this.rule = rule;
  }

  private void listImapFoldersAction(final TextBox targTextBox) {
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
    val chosen = TuiUtils.chooseFromList(gui, "Seleziona cartella", folders, tbDest.getText());
    if (chosen != null) tbDest.setText(chosen);
  }

  private void confirmAction(final Rule[] result) {
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
    close();
  }

  public Rule build() {

    setTitle(rule == null ? "Aggiungi Regola" : "Modifica Regola");
    setHints(List.of(Window.Hint.CENTERED, Window.Hint.MODAL));

    // fa sì che premendo ESC la finestra si chiuda
    // equivalente al click su "Annulla"
    setCloseWindowWithEscape(true);

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
    cbImap = new ComboBox<>(imapNames);

    cbSubject = new ComboBox<>(ConditionSubject.values());
    cbOperator = new ComboBox<>(ConditionOperator.values());
    tbValue =
        TuiUtils.requiredTextBox(
            60, rule != null ? LangUtils.nullToEmpty(rule.getConditionValue()) : "");
    cbAction = new ComboBox<>(ActionType.values());
    tbDest =
        TuiUtils.requiredTextBox(
            60, rule != null ? LangUtils.nullToEmpty(rule.getDestValue()) : "");

    btnPickFolder = new Button("Sfoglia cartelle…", () -> listImapFoldersAction(tbDest));

    if (rule != null) {
      cbImap.setSelectedItem(rule.getImapConfigName());
      cbSubject.setSelectedItem(rule.getConditionSubject());
      cbOperator.setSelectedItem(rule.getConditionOperator());
      tbValue.setText(LangUtils.nullToEmpty(rule.getConditionValue()));
      cbAction.setSelectedItem(rule.getActionType());
      tbDest.setText(LangUtils.nullToEmpty(rule.getDestValue()));
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
    buttons.addComponent(new Button("OK", () -> confirmAction(result)));

    buttons.addComponent(new EmptySpace(new TerminalSize(1, 1)));
    buttons.addComponent(new Button("Annulla", this::close));

    val outer = new Panel(new LinearLayout(Direction.VERTICAL));
    outer.addComponent(root);
    outer.addComponent(new EmptySpace(new TerminalSize(0, 1)));
    outer.addComponent(buttons);

    setComponent(outer);
    cbImap.takeFocus();
    gui.addWindowAndWait(this);
    return result[0];
  }
}
