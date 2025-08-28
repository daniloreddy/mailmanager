package org.danilorossi.mailmanager;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import jakarta.mail.Folder;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.UIDFolder;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.logging.Logger;
import lombok.Cleanup;
import lombok.Getter;
import lombok.NonNull;
import lombok.val;
import org.danilorossi.mailmanager.helpers.FileSystemUtils;
import org.danilorossi.mailmanager.helpers.LogConfigurator;
import org.danilorossi.mailmanager.model.ImapConfig;
import org.danilorossi.mailmanager.model.Rule;
import org.danilorossi.mailmanager.model.State;

public class MailManager {

  private static Gson gson = new Gson();
  private static final String IMAPS_FILE = "imap_config.json";
  protected static final String STATES_FILE = "processing_state.json";
  private static final String RULES_FILE = "rules.json";
  private static final Logger logger = Logger.getLogger(MailManager.class.getName());

  static {
    LogConfigurator.configLog(logger);
  }

  public static void main(String[] args) throws IOException {

    // Gestione parametro -help
    if (Arrays.asList(args).contains("-help")
        || Arrays.asList(args).contains("--help")
        || Arrays.asList(args).contains("-h")
        || Arrays.asList(args).contains("/?")) {
      System.out.println("Utilizzo: java -jar mailmanager.jar [opzione]");
      System.out.println("Opzioni:");
      System.out.println("  -help     Mostra questo messaggio di aiuto");
      System.out.println("  -imap     Avvia strumento di configurazione");
      System.out.println("  -rules    Avvia strumento di configurazione");
      System.out.println("  -tui      Avvia strumento di configurazione");
      System.out.println(
          "  (nessuna) Esegue l'elaborazione delle email con la configurazione e le regole"
              + " esistenti");
      return;
    }

    val mailManager = new MailManager();

    try {
      mailManager.loadImaps();
    } catch (IOException e) {
      new ConsoleTUI(mailManager).start();
      return;
    }

    mailManager.loadRules();
    mailManager.loadStates();

    // Modalità configurazione IMAP
    if (Arrays.asList(args).contains("-imap")) {
      new ConsoleTUI(mailManager).start();
      return;
    }

    // Modalità gestione regole
    if (Arrays.asList(args).contains("-rules")) {
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

    mailManager.saveStates();
  }

  @Getter private List<ImapConfig> imaps = new ArrayList<>(); // Lista di configurazioni IMAP
  @Getter private List<Rule> rules = new ArrayList<>(); //  Lista di regole
  @Getter private List<State> states = new ArrayList<>(); // Lista di stati
  private final Object statesLock = new Object();

  // Metodo per caricare le configurazioni IMAP da JSON
  public void loadImaps() throws IOException {
    val configFile = FileSystemUtils.getConfigFile(IMAPS_FILE);
    val listType = new TypeToken<ArrayList<ImapConfig>>() {}.getType();
    @Cleanup val reader = new FileReader(configFile);
    imaps = gson.fromJson(reader, listType);
    if (imaps == null) throw new IOException("File di configurazione IMAP non trovato.");
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
            String.format("File regole non trovato in %s.", rulesFile.getAbsolutePath()));
      } else {
        logger.info(
            String.format("Caricate %s recole da %s", rules.size(), rulesFile.getAbsolutePath()));
      }
    } catch (IOException | JsonSyntaxException e) {
      rules = new ArrayList<>();
      logger.warning(String.format("File regole non trovato in %s.", rulesFile.getAbsolutePath()));
    }
  }

  // Metodo per caricare stati da JSON
  public void loadStates() {
    val statesFile = FileSystemUtils.getConfigFile(STATES_FILE);
    try {
      @Cleanup val reader = new FileReader(statesFile);
      val listType = new TypeToken<ArrayList<State>>() {}.getType();
      states = gson.fromJson(reader, listType);
      if (states == null) {
        states = new ArrayList<>();
        logger.warning(
            String.format("File stati non trovato in %s.", statesFile.getAbsolutePath()));
      } else {
        logger.info(
            String.format("Caricati %s stati da %s", states.size(), statesFile.getAbsolutePath()));
      }
    } catch (IOException | JsonSyntaxException e) {
      states = new ArrayList<>();
      logger.warning(String.format("File stati non trovato in %s.", statesFile.getAbsolutePath()));
    }
  }

  /*
   public void processEmails() {
    for (val imap : imaps) {
      processEmails(imap);
    }
  }
  */

  public void processEmails() {
    val maxThreads =
        Math.min(
            imaps.size(),
            Math.max(
                Runtime.getRuntime().availableProcessors(), 8)); // I/O-bound: fino a 8 è spesso ok
    var pool = Executors.newFixedThreadPool(maxThreads);
    try {
      var tasks = new java.util.ArrayList<Callable<Void>>();
      for (var imap : imaps)
        tasks.add(
            () -> {
              processEmails(imap);
              return null;
            });
      pool.invokeAll(tasks); // attende la fine di tutti
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
    } finally {
      pool.shutdown();
    }
  }

  private State getOrCreateState(@NonNull String imapName) {
    synchronized (statesLock) {
      for (val state : states) if (imapName.equals(state.getImapConfigName())) return state;

      val state =
          State.builder().imapConfigName(imapName).uidValidity(0).lastProcessedUid(0).build();
      states.add(state);
      return state;
    }
  }

  private void processEmails(@NonNull final ImapConfig imap) {

    try {
      // Proprietà per la connessione IMAP con SSL
      val properties = imap.toProperties();

      // Crea una sessione
      val emailSession = Session.getInstance(properties);

      // Connetti allo store IMAP
      @Cleanup Store store = emailSession.getStore("imaps");
      store.connect(imap.getHost(), imap.getUsername(), imap.getPassword());

      // Apri la cartella Inbox in modalità read-write
      @Cleanup Folder folder = store.getFolder(imap.getInboxFolder());
      folder.open(Folder.READ_WRITE);

      // Per usare i metodi UID, dobbiamo castare il Folder a UIDFolder
      val uidFolder = ((UIDFolder) folder);
      val currentUidValidity = uidFolder.getUIDValidity();

      // protetta da lock
      val state = getOrCreateState(imap.getName());

      // Carica lo stato precedente
      long startUid = 1; // Default per la prima esecuzione
      if (state.getUidValidity() == currentUidValidity && state.getLastProcessedUid() > 0) {
        startUid = state.getLastProcessedUid() + 1;
        logger.info(String.format("Riparto da UID %s", startUid));
      } else {
        logger.warning("UIDVALIDITY cambiato o nessuno stato. Inizio dal primo messaggio.");
        // in alternativa, per NON riprocessare lo storico:
        // startUid = uidFolder.getUIDNext(); // processa solo i nuovi da ora in poi
      }

      val endUid = uidFolder.getUIDNext() - 1;
      if (endUid >= startUid) {
        val messages = uidFolder.getMessagesByUID(startUid, endUid);
        logger.info(String.format("Trovati %s nuovi messaggi da processare.", messages.length));
        for (val message : messages) {
          for (val rule : rules) {
            if (!Objects.equals(rule.getImapConfigName(), imap.getName())) continue;
            if (!rule.evaluate(message)) continue;
            rule.apply(message, folder, store);
            break;
          }
        }
        if (messages.length > 0) {
          val newLastUid = uidFolder.getUID(messages[messages.length - 1]);
          state.setUidValidity(currentUidValidity);
          state.setLastProcessedUid(newLastUid);
          logger.info(String.format("Aggiornato stato: lastUID=%s", newLastUid));
        }
      } else {
        // anche qui aggiorna l'uidValidity per consolidare lo stato del server
        state.setUidValidity(currentUidValidity);
        logger.info("Nessun nuovo messaggio.");
      }

      // Espelli i messaggi eliminati e chiudi (gestito da @Cleanup)
      folder.expunge();
      logger.info("Operazioni email completate.");
    } catch (Exception e) {
      logger.severe("Errore durante l'elaborazione delle email: " + e.getMessage());
    }
  }

  // Metodo per salvare la configurazione IMAP su JSON
  public void saveImaps() {
    val configFile = FileSystemUtils.getConfigFile(IMAPS_FILE);
    try {
      @Cleanup val writer = new FileWriter(configFile);
      gson.toJson(imaps, writer);
      logger.info(String.format("Salvata configurazione IMAP su ", configFile.getAbsolutePath()));
    } catch (IOException e) {
      logger.severe(
          String.format(
              "Errore durante il salvataggio della configurazione IMAP in %s: %s",
              configFile.getAbsolutePath(), e.getMessage()));
    }
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
          String.format(
              "Errore durante il salvataggio delle regole in %s: %s",
              rulesFile.getAbsolutePath(), e.getMessage()));
    }
  }

  private void saveStates() {
    val stateFile = FileSystemUtils.getConfigFile(STATES_FILE);
    try {
      @Cleanup val writer = new FileWriter(stateFile);
      gson.toJson(states, writer);
    } catch (IOException e) {
      logger.severe(
          String.format(
              "Errore durante il salvataggio del file di stato in %s: %s",
              stateFile.getAbsolutePath(), e.getMessage()));
    }
  }

  private State getState(@NonNull final String imapName) {
    for (val state : states) {
      if (Objects.equals(state.getImapConfigName(), imapName)) return state;
    }
    return null;
  }
}
