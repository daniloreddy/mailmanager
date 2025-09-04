package org.danilorossi.mailmanager.tui;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.Button;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.EmptySpace;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.TextBox;
import com.googlecode.lanterna.gui2.Window;
import com.googlecode.lanterna.gui2.dialogs.MessageDialog;
import com.googlecode.lanterna.gui2.dialogs.MessageDialogButton;
import com.googlecode.lanterna.gui2.table.Table;
import com.googlecode.lanterna.input.KeyType;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Folder;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import lombok.val;
import org.danilorossi.mailmanager.helpers.LangUtils;
import org.danilorossi.mailmanager.model.ImapConfig;

@UtilityClass
public class TuiUtils {

  public static List<String> fetchImapFolders(
      @NonNull final MultiWindowTextGUI gui, @NonNull final ImapConfig cfg) {
    try {
      val props = cfg.toProperties();
      val sess = Session.getInstance(props);
      try (val store = sess.getStore(cfg.getStoreProtocol())) {
        store.connect(cfg.getHost(), cfg.getUsername(), cfg.getPassword());
        val out = new ArrayList<String>();
        val def = store.getDefaultFolder();
        for (val f : (def.list("*") != null ? def.list("*") : new Folder[0])) {
          if ((f.getType() & Folder.HOLDS_MESSAGES) != 0) out.add(f.getFullName());
        }
        return out;
      }
    } catch (AuthenticationFailedException ex) {
      error(gui, "Autenticazione IMAP: Credenziali non valide.");
      return null;
    } catch (MessagingException ex) {
      error(gui, "Errore IMAP: " + LangUtils.rootCauseMsg(ex));
      return null;
    }
  }

  public static String chooseFromList(
      @NonNull final MultiWindowTextGUI gui,
      @NonNull final String title,
      @NonNull final List<String> items,
      final String preselect) {

    if (items.isEmpty()) {
      info(gui, "Nessun elemento disponibile.");
      return null;
    }

    val dialog = new BasicWindow(title);
    dialog.setHints(List.of(Window.Hint.CENTERED, Window.Hint.MODAL));
    dialog.setCloseWindowWithEscape(true);

    val table = new Table<>("Elementi");
    table.setPreferredSize(new TerminalSize(70, 16));
    for (val it : items) table.getTableModel().addRow(it);
    val preIndex = preselect == null ? -1 : items.indexOf(preselect);
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

  /** Overload comodo: testa direttamente un ImapConfig già costruito. */
  public static String tryTestImap(@NonNull ImapConfig cfg) {
    try {
      val props = cfg.toProperties();
      val sess = Session.getInstance(props);
      val proto = cfg.getStoreProtocol();

      try (val store = sess.getStore(proto)) {
        store.connect(cfg.getHost(), cfg.getUsername(), cfg.getPassword());
      }
      return null;
    } catch (Exception ex) {
      return LangUtils.rootCauseMsg(ex);
    }
  }

  public static String tryTestImap(
      @NonNull String host, @NonNull String port, @NonNull String user, @NonNull String pass) {
    try {
      val cfg =
          ImapConfig.builder()
              .name("test")
              .host(host.trim())
              .port(port.trim())
              .username(user.trim())
              .password(pass) // non trimmo la password
              .inboxFolder("INBOX")
              .build();
      return tryTestImap(cfg);
    } catch (Exception ex) {
      return LangUtils.rootCauseMsg(ex);
    }
  }

  /**
   * Crea una TextBox che accetta solo cifre (maxDigits opzionale). Utile per porte, timeout, ecc.
   */
  public static TextBox numericBox(final int maxDigits, final String initial) {
    val regex =
        maxDigits > 0 ? Pattern.compile("^\\d{0," + maxDigits + "}$") : Pattern.compile("^\\d*$");

    val tb = new TextBox().setValidationPattern(regex);
    if (initial != null) tb.setText(initial);

    // Blocca digitazione non numerica (firma corretta: (Interactable, KeyStroke))
    tb.setInputFilter(
        (interactable, key) -> {
          if (key.getKeyType() == KeyType.Character) {
            val ch = key.getCharacter();
            if (!Character.isDigit(ch)) return false;

            // Leggi il testo corrente dal componente
            val current = ((TextBox) interactable).getText();
            if (maxDigits > 0 && current.length() >= maxDigits) return false;

            return true;
          }
          // consenti backspace, frecce, ecc.
          return switch (key.getKeyType()) {
            case Backspace, Delete, ArrowLeft, ArrowRight, Home, End, Tab, Enter -> true;
            default -> true;
          };
        });

    return tb;
  }

  /** Shortcut per porta: TextBox numerica (max 5 cifre). */
  public static TextBox portBox(final String initial) {
    return numericBox(5, initial);
  }

  /** Shortcut per timeout (ms): TextBox numerica (fino a 9 cifre). */
  public static TextBox timeoutBox(final String initial) {
    return numericBox(9, initial);
  }

  /**
   * TextBox "required" con larghezza fissa; la validazione effettiva va fatta con
   * validateRequired(...).
   */
  public static TextBox requiredTextBox(final int width, final String initial) {
    val tb = new TextBox(new TerminalSize(Math.max(1, width), 1));
    if (initial != null) tb.setText(initial);
    return tb;
  }

  /** Mostra errore se il TextBox è vuoto o solo spazi. Ritorna true se valido. */
  public static boolean validateRequired(
      @NonNull final MultiWindowTextGUI gui,
      @NonNull final String fieldLabel,
      @NonNull final TextBox tb) {
    val v = tb.getText() == null ? "" : tb.getText().trim();
    if (v.isEmpty()) {
      error(gui, "Il campo \"" + fieldLabel + "\" è obbligatorio.");
      tb.takeFocus();
      return false;
    }
    return true;
  }

  /** Valida che la porta sia intero 1..65535. Ritorna true se valida. */
  public static boolean validatePort(
      @NonNull final MultiWindowTextGUI gui, @NonNull final TextBox portTb) {
    val s = portTb.getText();
    val p = LangUtils.parseIntOr(s, -1);
    if (p < 1 || p > 65535) {
      error(gui, "Porta non valida. Usa un intero tra 1 e 65535.");
      portTb.takeFocus();
      return false;
    }
    return true;
  }

  /** Valida un intero in range [min..max]. Ritorna true se valido. */
  public static boolean validateIntRange(
      @NonNull final MultiWindowTextGUI gui,
      @NonNull final String fieldLabel,
      @NonNull final TextBox tb,
      final int min,
      final int max) {
    val s = tb.getText();
    val v = LangUtils.parseIntOr(s, Integer.MIN_VALUE);
    if (v < min || v > max) {
      error(
          gui,
          "Valore non valido per \""
              + fieldLabel
              + "\". Range consentito: "
              + min
              + "–"
              + max
              + ".");
      tb.takeFocus();
      return false;
    }
    return true;
  }

  /** Conferma sì/no. Ritorna true se l'utente preme Yes. */
  public static boolean confirm(
      @NonNull final MultiWindowTextGUI gui,
      @NonNull final String title,
      @NonNull final String message) {
    return MessageDialog.showMessageDialog(
            gui, title, message, MessageDialogButton.Yes, MessageDialogButton.No)
        == MessageDialogButton.Yes;
  }

  /* ===================== Message helpers (facoltativi) ===================== */

  public static void info(@NonNull final MultiWindowTextGUI gui, @NonNull final String msg) {
    MessageDialog.showMessageDialog(gui, "Info", msg, MessageDialogButton.OK);
  }

  public static void warn(@NonNull final MultiWindowTextGUI gui, @NonNull final String msg) {
    MessageDialog.showMessageDialog(gui, "Attenzione", msg, MessageDialogButton.OK);
  }

  public static void error(@NonNull final MultiWindowTextGUI gui, @NonNull final String msg) {
    MessageDialog.showMessageDialog(gui, "Errore", msg, MessageDialogButton.OK);
  }
}
