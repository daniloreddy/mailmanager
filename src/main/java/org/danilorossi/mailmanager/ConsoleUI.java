package org.danilorossi.mailmanager;

import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

import javax.mail.Folder;
import javax.mail.MessagingException;
import javax.mail.Session;

import lombok.Cleanup;
import lombok.NonNull;
import lombok.val;

public class ConsoleUI {
  private final MailManager mailManager;
  private final Scanner scanner;

  public ConsoleUI(@NonNull MailManager mailManager) {
    this.mailManager = mailManager;
    this.scanner = new Scanner(System.in);
  }

  private void addRule() {
    System.out.println("\n=== Aggiungi Regola ===");
    val subject = promptEnum("Soggetto", ConditionSubject.values());
    val operator = promptEnum("Operatore", ConditionOperator.values());
    val value = promptString("Valore della condizione (es. testo da cercare)", true);
    val action = promptEnum("Azione", ActionType.values());
    val destValue =
        action == ActionType.DELETE
            ? null
            : promptFolder("Cartella di destinazione", action != ActionType.DELETE, null);

    val rule =
        Rule.builder()
            .conditionSubject(subject)
            .conditionOperator(operator)
            .conditionValue(value)
            .actionType(action)
            .destValue(destValue)
            .build();

    mailManager.getRules().add(rule);
    mailManager.saveRules();
    System.out.println("Regola aggiunta con successo!");
  }

  private void deleteRule() {
    val rules = mailManager.getRules();
    if (rules.isEmpty()) {
      System.out.println("Nessuna regola da eliminare.");
      return;
    }

    System.out.println("\n=== Elimina Regola ===");
    val index = promptIndex("Inserisci il numero della regola da eliminare", rules.size());
    if (index == -1) {
      System.out.println("Indice non valido.");
      return;
    }

    rules.remove(index);
    mailManager.saveRules();
    System.out.println("Regola eliminata con successo!");
  }

  private void editRule() {
    val rules = mailManager.getRules();
    if (rules.isEmpty()) {
      System.out.println("Nessuna regola da modificare.");
      return;
    }

    System.out.println("\n=== Modifica Regola ===");
    val index = promptIndex("Inserisci il numero della regola da modificare", rules.size());
    if (index == -1) {
      System.out.println("Indice non valido.");
      return;
    }

    val rule = rules.get(index);
    System.out.println("Regola attuale: " + rule);
    System.out.println("Inserisci nuovi valori (premi Invio per mantenere il valore attuale):");

    val subject =
        promptEnum(
            "Soggetto [" + rule.getConditionSubject() + "]",
            ConditionSubject.values(),
            rule.getConditionSubject());
    val operator =
        promptEnum(
            "Operatore [" + rule.getConditionOperator() + "]",
            ConditionOperator.values(),
            rule.getConditionOperator());
    val value =
        promptString(
            "Valore della condizione [" + rule.getConditionValue() + "]",
            true,
            rule.getConditionValue());
    val action =
        promptEnum(
            "Azione [" + rule.getActionType() + "]", ActionType.values(), rule.getActionType());
    val destValue =
        action == ActionType.DELETE
            ? null
            : promptFolder(
                "Cartella di destinazione ["
                    + (rule.getDestValue() != null ? rule.getDestValue() : "")
                    + "]",
                action != ActionType.DELETE,
                rule.getDestValue());

    val newRule =
        Rule.builder()
            .conditionSubject(subject)
            .conditionOperator(operator)
            .conditionValue(value)
            .actionType(action)
            .destValue(destValue)
            .build();

    rules.set(index, newRule);
    mailManager.saveRules();
    System.out.println("Regola modificata con successo!");
  }

  private List<String> fetchImapFolders() {
    try {
      val config = mailManager.getImapConfig();
      val properties = config.toProperties();  
      val emailSession = Session.getInstance(properties);
      @Cleanup val store = emailSession.getStore("imaps");
      store.connect(config.getHost(), config.getUsername(), config.getPassword());

      val folders = new ArrayList<String>();
      val defaultFolder = store.getDefaultFolder();
      for (val folder : defaultFolder.list("*")) {
        if ((folder.getType() & Folder.HOLDS_MESSAGES) != 0) {
          folders.add(folder.getFullName());
        }
      }
      return folders;
    } catch (MessagingException e) {
      System.err.println("Errore di connessione al server IMAP: " + e.getMessage());
      return null;
    }
  }

  private <T extends Enum<T>> T promptEnum(String prompt, T[] values) {
    return promptEnum(prompt, values, null);
  }

  private <T extends Enum<T>> T promptEnum(
      @NonNull final String prompt, @NonNull final T[] values, T defaultValue) {
    System.out.println(prompt + ":");
    for (int i = 0; i < values.length; i++) {
      System.out.println((i + 1) + ". " + values[i]);
    }
    System.out.print(
        "Scelta (1-"
            + values.length
            + ")"
            + (defaultValue != null ? " [predefinito: " + defaultValue + "]" : "")
            + ": ");
    val input = scanner.nextLine().trim();
    if (input.equalsIgnoreCase("help")) {
      showRulesHelp();
      return promptEnum(prompt, values, defaultValue);
    }
    if (input.isEmpty() && defaultValue != null) {
      return defaultValue;
    }
    try {
      val index = Integer.parseInt(input) - 1;
      if (index >= 0 && index < values.length) {
        return values[index];
      }
    } catch (NumberFormatException e) {
      // Ignora e riprova
    }
    System.out.println("Scelta non valida. Riprova.");
    return promptEnum(prompt, values, defaultValue);
  }

  private String promptFolder(@NonNull final String prompt, boolean required, String defaultValue) {
    val folders = fetchImapFolders();
    if (folders != null && !folders.isEmpty()) {
      System.out.println(prompt + ":");
      for (int i = 0; i < folders.size(); i++) {
        System.out.println((i + 1) + ". " + folders.get(i));
      }
      System.out.print(
          "Scelta (1-"
              + folders.size()
              + ")"
              + (defaultValue != null ? " [predefinito: " + defaultValue + "]" : "")
              + ", o inserisci manualmente: ");
      val input = scanner.nextLine().trim();
      if (input.equalsIgnoreCase("help")) {
        showRulesHelp();
        return promptFolder(prompt, required, defaultValue);
      }
      if (input.isEmpty() && defaultValue != null) {
        return defaultValue;
      }
      try {
        val index = Integer.parseInt(input) - 1;
        if (index >= 0 && index < folders.size()) {
          return folders.get(index);
        }
      } catch (NumberFormatException e) {
        // Input manuale
        if (!input.isEmpty()) {
          return input;
        }
      }
    } else {
      System.out.println(
          "Errore di connessione al server IMAP. Inserisci manualmente il nome della cartella.");
      System.out.print(
          prompt + (defaultValue != null ? " [predefinito: " + defaultValue + "]" : "") + ": ");
      val input = scanner.nextLine().trim();
      if (input.equalsIgnoreCase("help")) {
        showRulesHelp();
        return promptFolder(prompt, required, defaultValue);
      }
      if (input.isEmpty() && defaultValue != null) {
        return defaultValue;
      }
      if (!input.isEmpty()) {
        return input;
      }
    }
    if (required) {
      System.out.println("Questo campo è obbligatorio.");
      return promptFolder(prompt, required, defaultValue);
    }
    return null;
  }

  private int promptIndex(@NonNull final String prompt, int maxIndex) {
    System.out.print(prompt + " (1-" + maxIndex + "): ");
    val input = scanner.nextLine().trim();
    if (input.equalsIgnoreCase("help")) {
      showRulesHelp();
      return promptIndex(prompt, maxIndex);
    }
    try {
      val index = Integer.parseInt(input) - 1;
      if (index >= 0 && index < maxIndex) {
        return index;
      }
    } catch (NumberFormatException e) {
      // Ignora e riprova
    }
    return -1;
  }

  private String promptString(String prompt, boolean required) {
    return promptString(prompt, required, null);
  }

  private String promptString(@NonNull final String prompt, boolean required, String defaultValue) {
    System.out.print(prompt + ": ");
    val input = scanner.nextLine().trim();
    if (input.equalsIgnoreCase("help")) {
      showRulesHelp();
      return promptString(prompt, required, defaultValue);
    }
    if (input.isEmpty() && defaultValue != null) {
      return defaultValue;
    }
    if (required && input.isEmpty()) {
      System.out.println("Questo campo è obbligatorio.");
      return promptString(prompt, required, defaultValue);
    }
    return input;
  }

  public void showImapConfigMenu() {
    System.out.println("\n=== Configurazione Server IMAP ===");
    System.out.println("Inserisci 'help' per visualizzare un aiuto.");
    val currentConfig = mailManager.getImapConfig();
    System.out.println("Configurazione attuale:");
    System.out.println("Host: " + currentConfig.getHost());
    System.out.println("Porta: " + currentConfig.getPort());
    System.out.println("Username: " + currentConfig.getUsername());
    System.out.println(
        "Password: " + (currentConfig.getPassword().isEmpty() ? "<non impostata>" : "********"));
    System.out.println("Cartella Inbox: " + currentConfig.getInboxFolder());
    System.out.println("\nInserisci nuovi valori (premi Invio per mantenere il valore attuale):");

    System.out.print("Host [" + currentConfig.getHost() + "]: ");
    val hostInput = scanner.nextLine().trim();
    if (hostInput.equalsIgnoreCase("help")) {
      showImapHelp();
      return;
    }
    val host = hostInput.isEmpty() ? currentConfig.getHost() : hostInput;

    System.out.print("Porta [" + currentConfig.getPort() + "]: ");
    val portInput = scanner.nextLine().trim();
    if (portInput.equalsIgnoreCase("help")) {
      showImapHelp();
      return;
    }
    val port = portInput.isEmpty() ? currentConfig.getPort() : portInput;

    System.out.print("Username [" + currentConfig.getUsername() + "]: ");
    val usernameInput = scanner.nextLine().trim();
    if (usernameInput.equalsIgnoreCase("help")) {
      showImapHelp();
      return;
    }
    val username = usernameInput.isEmpty() ? currentConfig.getUsername() : usernameInput;

    System.out.print("Password (premi Invio per mantenere quella attuale o usare MAIL_PASSWORD): ");
    val passwordInput = scanner.nextLine().trim();
    if (passwordInput.equalsIgnoreCase("help")) {
      showImapHelp();
      return;
    }
    val envPassword = System.getenv("MAIL_PASSWORD");
    val password =
        passwordInput.isEmpty()
            ? (envPassword != null && !envPassword.isEmpty()
                ? envPassword
                : currentConfig.getPassword())
            : passwordInput;

    System.out.print("Cartella Inbox [" + currentConfig.getInboxFolder() + "]: ");
    val inboxFolderInput = scanner.nextLine().trim();
    if (inboxFolderInput.equalsIgnoreCase("help")) {
      showImapHelp();
      return;
    }
    val inboxFolder =
        inboxFolderInput.isEmpty() ? currentConfig.getInboxFolder() : inboxFolderInput;

    // Validazione
    if (host.isEmpty() || port.isEmpty() || username.isEmpty() || inboxFolder.isEmpty()) {
      System.out.println("Errore: Host, porta, username e cartella inbox sono obbligatori.");
      return;
    }

    val newConfig =
        ImapConfig.builder()
            .host(host)
            .port(port)
            .username(username)
            .password(password)
            .inboxFolder(inboxFolder)
            .build();

    mailManager.saveImapConfig(newConfig);
    System.out.println("Configurazione IMAP salvata con successo!");
  }

  private void showImapHelp() {
    System.out.println("\n=== Aiuto Configurazione IMAP ===");
    System.out.println("Inserisci i dettagli per connetterti al server IMAP:");
    System.out.println("- Host: l'indirizzo del server IMAP (es. imap.gmail.com)");
    System.out.println("- Porta: la porta IMAP (es. 993 per IMAP con SSL)");
    System.out.println("- Username: il tuo indirizzo email o username");
    System.out.println(
        "- Password: la password del tuo account (può essere fornita tramite la variabile d'ambiente MAIL_PASSWORD)");
    System.out.println("- Cartella Inbox: la cartella da elaborare (es. INBOX)");
    System.out.println(
        "Premi Invio per mantenere il valore attuale. Riprova dopo aver letto l'aiuto.");
    System.out.println("=== Fine Aiuto ===");
  }

  private void showRulesHelp() {
    System.out.println("\n=== Aiuto Gestione Regole ===");
    System.out.println(
        "Le regole definiscono come elaborare le email in base a condizioni e azioni.");
    System.out.println("Opzioni disponibili:");
    System.out.println(
        "- Aggiungi regola: Crea una nuova regola specificando soggetto, operatore, valore, azione e valore azione (se applicabile).");
    System.out.println(
        "- Modifica regola: Modifica una regola esistente selezionando il suo numero.");
    System.out.println(
        "- Elimina regola: Rimuove una regola esistente selezionando il suo numero.");
    System.out.println("- Esci: Salva le modifiche e torna al menu principale.");
    System.out.println("Esempio di regola: 'SUBJECT CONTAINS \"test\" -> MOVE \"TestFolder\"'");
    System.out.println("=== Fine Aiuto ===");
  }

  public void showRulesMenu() {
    while (true) {
      System.out.println("\n=== Gestione Regole ===");
      System.out.println("Inserisci 'help' per visualizzare un aiuto.");
      System.out.println("Regole attuali:");
      val rules = mailManager.getRules();
      if (rules.isEmpty()) {
        System.out.println("Nessuna regola presente.");
      } else {
        for (int i = 0; i < rules.size(); i++) {
          System.out.println((i + 1) + ". " + rules.get(i));
        }
      }
      System.out.println("\nOpzioni:");
      System.out.println("1. Aggiungi regola");
      System.out.println("2. Modifica regola");
      System.out.println("3. Elimina regola");
      System.out.println("4. Esci");
      System.out.print("Scelta: ");

      val choice = scanner.nextLine().trim();
      if (choice.equalsIgnoreCase("help")) {
        showRulesHelp();
        continue;
      }
      switch (choice) {
        case "1":
          addRule();
          break;
        case "2":
          editRule();
          break;
        case "3":
          deleteRule();
          break;
        case "4":
          mailManager.saveRules();
          System.out.println("Uscita dalla gestione regole.");
          return;
        default:
          System.out.println("Scelta non valida. Riprova.");
      }
    }
  }
}
