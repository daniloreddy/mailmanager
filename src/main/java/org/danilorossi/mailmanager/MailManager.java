package org.danilorossi.mailmanager;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.Session;
import javax.mail.Store;
import javax.mail.UIDFolder;

import org.danilorossi.mailmanager.helpers.FileSystemUtils;
import org.danilorossi.mailmanager.helpers.LogConfigurator;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import lombok.Cleanup;
import lombok.Getter;
import lombok.NonNull;
import lombok.val;

public class MailManager {

  private static Gson gson = new Gson();
  private static final String IMAP_CONFIG_FILE = "imap_config.json";
  protected static final String STATE_FILE = "processing_state.json";
  private static final String RULES_FILE = "rules.json";
  private static final Logger logger = Logger.getLogger(MailManager.class.getName());

  static {
    LogConfigurator.configLog(logger);
  }

  public static void main(String[] args) throws IOException {

    // Gestione parametro -help
    if (Arrays.asList(args).contains("-help")) {
      System.out.println("Utilizzo: java -jar mailmanager.jar [opzione]");
      System.out.println("Opzioni:");
      System.out.println("  -help     Mostra questo messaggio di aiuto");
      System.out.println("  -imap     Avvia strumento di configurazione");
      System.out.println("  -rules    Avvia strumento di configurazione");
      System.out.println("  -tui      Avvia strumento di configurazione");
      System.out.println("  (nessuna) Esegue l'elaborazione delle email con la configurazione e le regole esistenti");
      return;
    }

    val mailManager = new MailManager();

    try {
      mailManager.loadImapConfig();
    } catch (IOException e) {
      //      new ConsoleUI(mailManager).showImapConfigMenu();
      new ConsoleTUI(mailManager).start();
      return;
    }

    mailManager.loadRules();

    // Modalità configurazione IMAP
    if (Arrays.asList(args).contains("-imap")) {
      //      new ConsoleUI(mailManager).showImapConfigMenu();
      new ConsoleTUI(mailManager).start();
      return;
    }

    // Modalità gestione regole
    if (Arrays.asList(args).contains("-rules")) {
      //      new ConsoleUI(mailManager).showRulesMenu();
      new ConsoleTUI(mailManager).start();
      return;
    }

    // Modalità gestione regole
    if (Arrays.asList(args).contains("-tui")) {
      new ConsoleTUI(mailManager).start();
      return;
    }

    // Modalità elaborazione email
    mailManager.processEmails();
  }

  @Getter private ImapConfig imapConfig = ImapConfig.builder().build();

  @Getter private List<Rule> rules = new ArrayList<>();

  // Metodo per caricare la configurazione IMAP da JSON
  public void loadImapConfig() throws IOException {
    val configFile = FileSystemUtils.getConfigFile(IMAP_CONFIG_FILE);
    @Cleanup val reader = new FileReader(configFile);
    imapConfig = gson.fromJson(reader, ImapConfig.class);
    if (imapConfig == null) throw new IOException("File di configurazione IMAP non trovato.");

    // Sovrascrivi la password con quella dell'ambiente, se presente
    val envPassword = System.getenv("MAIL_PASSWORD");
    if (envPassword != null && !envPassword.isEmpty()) {
      imapConfig =
          ImapConfig.builder()
              .host(imapConfig.getHost())
              .port(imapConfig.getPort())
              .username(imapConfig.getUsername())
              .password(envPassword)
              .inboxFolder(imapConfig.getInboxFolder())
              .build();
    }
    logger.info("Caricata configurazione IMAP da file.");
  }

  // Metodo per caricare regole da JSON
  public void loadRules() {
    val rulesFile = FileSystemUtils.getConfigFile(RULES_FILE);
    try {
      @Cleanup val reader = new FileReader(rulesFile);
      val listType = new TypeToken<ArrayList<Rule>>() {}.getType();
      rules = gson.fromJson(reader, listType);
      if (rules == null) {
        rules = new ArrayList<>();
        logger.warning(
            "File regole non trovato in "
                + rulesFile.getAbsolutePath()
                + ". Inizio con lista vuota.");
      } else {
        logger.info("Caricate " + rules.size() + " regole da " + rulesFile.getAbsolutePath());
      }
    } catch (IOException e) {
      logger.warning(
          "File regole non trovato in "
              + rulesFile.getAbsolutePath()
              + ". Inizio con lista vuota.");
    }
  }

  private State loadState() {
    val stateFile = FileSystemUtils.getConfigFile(STATE_FILE);
    if (!stateFile.exists()) return null;
    try {
      @Cleanup val reader = new FileReader(stateFile);
      return gson.fromJson(reader, State.class);
    } catch (IOException e) {
      logger.warning("Impossibile caricare il file di stato: " + e.getMessage());
      return null;
    }
  }

  private void saveState(State state) {
    val stateFile = FileSystemUtils.getConfigFile(STATE_FILE);
    try {
      @Cleanup val writer = new FileWriter(stateFile);
      gson.toJson(state, writer);
    } catch (IOException e) {
      logger.severe("Errore durante il salvataggio del file di stato: " + e.getMessage());
    }
  }

  public void processEmails() {

    try {
      // Proprietà per la connessione IMAP con SSL
      val properties = imapConfig.toProperties();

      // Crea una sessione
      val emailSession = Session.getInstance(properties);

      // Connetti allo store IMAP
      @Cleanup Store store = emailSession.getStore("imaps");
      store.connect(imapConfig.getHost(), imapConfig.getUsername(), imapConfig.getPassword());

      // Apri la cartella Inbox in modalità read-write
      @Cleanup Folder folder = store.getFolder(imapConfig.getInboxFolder());
      folder.open(Folder.READ_WRITE);

      // Per usare i metodi UID, dobbiamo castare il Folder a UIDFolder
      val uidFolder = ((UIDFolder) folder);

      // Carica lo stato precedente
      long startUid = 1; // Default per la prima esecuzione
      val lastState = loadState();
      val currentUidValidity = uidFolder.getUIDValidity();

      // Controlla la validità e imposta l'UID di partenza
      if (lastState != null && lastState.getUidValidity() == currentUidValidity) {
        startUid = lastState.getLastProcessedUid() + 1;
        logger.info(
            "Stato precedente caricato. Processo i messaggi a partire dall'UID: " + startUid);
      } else {
        logger.warning(
            "Nessuno stato precedente valido trovato o UIDVALIDITY cambiato. Inizio dal primo messaggio.");
      }

      // Ottieni l'UID dell'ultimo messaggio
      val endUid = uidFolder.getUIDNext() - 1;

      final Message[] messages;
      if (endUid >= startUid) {
        // Scarica SOLO i nuovi messaggi
        messages = uidFolder.getMessagesByUID(startUid, endUid);
        logger.info("Trovati " + messages.length + " nuovi messaggi da processare.");
      } else {
        logger.info("Nessun nuovo messaggio da processare.");
        messages = new Message[0];
      }

      // Itera sui messaggi e applica le regole
      for (val message : messages) {
        for (val rule : rules) {
          if (rule.evaluate(message)) {
            rule.apply(message, folder, store);
            break; // la prima regola interrompe l'esecuzione delle successive
          }
        }
      }

      // Salva il nuovo stato
      if (messages.length > 0) {
        val newLastUid = uidFolder.getUID(messages[messages.length - 1]);
        val newState = new State();
        newState.setUidValidity(currentUidValidity);
        newState.setLastProcessedUid(newLastUid);
        saveState(newState);
        logger.info("Nuovo stato salvato. Ultimo UID processato: " + newLastUid);
      }

      // Espelli i messaggi eliminati e chiudi (gestito da @Cleanup)
      folder.expunge();
      logger.info("Operazioni email completate.");
    } catch (Exception e) {
      logger.severe("Errore durante l'elaborazione delle email: " + e.getMessage());
    }
  }

  // Metodo per salvare la configurazione IMAP su JSON
  public void saveImapConfig() {
    val configFile = FileSystemUtils.getConfigFile(IMAP_CONFIG_FILE);
    try {
      @Cleanup val writer = new FileWriter(configFile);
      gson.toJson(imapConfig, writer);
      logger.info("Salvata configurazione IMAP su " + configFile.getAbsolutePath());
    } catch (IOException e) {
      logger.severe(
          "Errore durante il salvataggio della configurazione IMAP in "
              + configFile.getAbsolutePath()
              + ": "
              + e.getMessage());
    }
  }

  public void saveImapConfig(@NonNull final ImapConfig imapConfig) {
    this.imapConfig = imapConfig;
    saveImapConfig();
  }

  // Metodo per salvare regole su JSON
  public void saveRules() {
    val rulesFile = FileSystemUtils.getConfigFile(RULES_FILE);
    try {
      @Cleanup val writer = new FileWriter(rulesFile);
      gson.toJson(rules, writer);
      logger.info("Salvate " + rules.size() + " regole su " + rulesFile.getAbsolutePath());
    } catch (IOException e) {
      logger.severe(
          "Errore durante il salvataggio delle regole in "
              + rulesFile.getAbsolutePath()
              + ": "
              + e.getMessage());
    }
  }
}
