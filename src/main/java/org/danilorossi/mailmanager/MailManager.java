package org.danilorossi.mailmanager;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import jakarta.mail.Folder;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.UIDFolder;
import java.io.FileReader;
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
import org.danilorossi.mailmanager.helpers.LangUtils;
import org.danilorossi.mailmanager.helpers.LogConfigurator;
import org.danilorossi.mailmanager.model.ImapConfig;
import org.danilorossi.mailmanager.model.Rule;
import org.danilorossi.mailmanager.model.State;

public class MailManager {

  private static Gson GSON = new Gson();
  private static final Logger LOG = Logger.getLogger(MailManager.class.getName());

  static {
    LogConfigurator.configLog(LOG);
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
    val configFile = FileSystemUtils.getImapServersJson().toFile();
    val listType = new TypeToken<ArrayList<ImapConfig>>() {}.getType();
    @Cleanup val reader = new FileReader(configFile);
    imaps = GSON.fromJson(reader, listType);
    if (imaps == null) throw new IOException("File di configurazione IMAP non trovato.");
    LangUtils.info(LOG, "Caricata configurazione IMAP da file.");
  }

  // Metodo per caricare regole da JSON
  public void loadRules() {
    val rulesFile = FileSystemUtils.getRulesJson().toFile();
    try {
      @Cleanup val reader = new FileReader(rulesFile);
      val listType = new TypeToken<ArrayList<Rule>>() {}.getType();
      rules = GSON.fromJson(reader, listType);
      if (rules == null) {
        rules = new ArrayList<>();
        LangUtils.warn(LOG, "File regole non trovato in {}", rulesFile.getAbsolutePath());
      } else {
        LangUtils.info(LOG, "Caricate %s recole da {}", rules.size(), rulesFile.getAbsolutePath());
      }
    } catch (IOException | JsonSyntaxException e) {
      rules = new ArrayList<>();
      LangUtils.warn(LOG, "File regole non trovato in {}.", rulesFile.getAbsolutePath());
    }
  }

  // Metodo per caricare stati da JSON
  public void loadStates() {
    val statesFile = FileSystemUtils.getProcessingStateJson().toFile();
    try {
      @Cleanup val reader = new FileReader(statesFile);
      val listType = new TypeToken<ArrayList<State>>() {}.getType();
      states = GSON.fromJson(reader, listType);
      if (states == null) {
        states = new ArrayList<>();
        LangUtils.warn(LOG, "File stati non trovato in {}.", statesFile.getAbsolutePath());
      } else {
        LangUtils.info(LOG, "Caricati {} stati da {}", states.size(), statesFile.getAbsolutePath());
      }
    } catch (IOException | JsonSyntaxException e) {
      states = new ArrayList<>();
      LangUtils.warn(LOG, "File stati non trovato in {}.", statesFile.getAbsolutePath());
    }
  }

  public void processEmails() {
    val maxThreads =
        !LangUtils.emptyString(System.getenv("DEV"))
            ? 1
            : Math.min(
                imaps.size(), Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors())));
    val pool = Executors.newFixedThreadPool(maxThreads);
    try {
      val tasks = new java.util.ArrayList<Callable<Void>>();
      for (val imap : imaps)
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

  // stato cartella-specifico
  private State getOrCreateState(@NonNull String imapName, @NonNull String folderName) {
    synchronized (statesLock) {
      val k = State.keyOf(imapName, folderName);
      for (val s : states)
        if (k.equals(State.keyOf(s.getImapConfigName(), s.getFolder()))) return s;
      val s =
          State.builder()
              .imapConfigName(imapName)
              .folder(folderName)
              .uidValidity(-1L)
              .lastProcessedUid(1L)
              .updatedAtEpochMs(System.currentTimeMillis())
              .build();
      states.add(s);
      return s;
    }
  }

  private void processEmails(@NonNull final ImapConfig imap) {

    try {
      // Proprietà per la connessione IMAP con SSL
      val properties = imap.toProperties();

      // Crea una sessione
      val emailSession = Session.getInstance(properties);

      // Connetti allo store IMAP
      @Cleanup Store store = emailSession.getStore(imap.getStoreProtocol());
      store.connect(imap.getHost(), imap.getUsername(), imap.getPassword());

      // Apri la cartella Inbox in modalità read-write
      @Cleanup Folder folder = store.getFolder(imap.getInboxFolder());
      folder.open(Folder.READ_WRITE);

      // Per usare i metodi UID, dobbiamo castare il Folder a UIDFolder
      val uidFolder = ((UIDFolder) folder);
      val currentUidValidity = uidFolder.getUIDValidity();

      // Stato "per cartella"
      State state;
      synchronized (statesLock) {
        state =
            getOrCreateState(imap.getName(), imap.getInboxFolder())
                .refreshValidity(currentUidValidity);
        // rimpiazza l'istanza aggiornata nella lista (in caso usi @With e immutabilità)
        replaceState(state);
      }

      val uidNext = uidFolder.getUIDNext();
      if (uidNext <= 0) {
        LangUtils.info(LOG, "La casella sembra vuota (UIDNEXT <= 0).");
        synchronized (statesLock) {
          replaceState(
              state
                  .withUidValidity(currentUidValidity)
                  .withUpdatedAtEpochMs(System.currentTimeMillis()));
        }
        return;
      }

      // range da leggere: dall’ultimo processato + 1 fino a UIDNEXT-1
      val startUid = (state.getLastProcessedUid() > 0) ? state.getLastProcessedUid() + 1 : 1;
      long endUid = uidNext - 1;
      if (startUid > endUid) {
        LangUtils.info(LOG, "Nessun nuovo messaggio.");
        // comunque consolida UIDVALIDITY aggiornato
        synchronized (statesLock) {
          replaceState(
              state
                  .withUidValidity(currentUidValidity)
                  .withUpdatedAtEpochMs(System.currentTimeMillis()));
        }
        return;
      }

      val messages = uidFolder.getMessagesByUID(startUid, endUid);
      LangUtils.info(LOG, "Trovati {} messaggi (UID {}..{}).", messages.length, startUid, endUid);

      long lastSeenUid = state.getLastProcessedUid();

      for (val message : messages) {
        long uid = uidFolder.getUID(message);
        if (uid <= 0) continue;

        // controllo stato (double-check: uidValidity e monotonia)
        if (!state.shouldProcess(uid, currentUidValidity)) {
          lastSeenUid = Math.max(lastSeenUid, uid); // tieni comunque traccia del massimo visto
          continue;
        }

        // valuta regole: prima che matcha, applichi e stop (come fai ora)
        boolean acted = false;
        for (val rule : rules) {
          if (!Objects.equals(rule.getImapConfigName(), imap.getName()))
            continue; // regola non per questa config
          if (!rule.evaluate(message)) continue; // non matcha
          rule.apply(message, folder, store);
          acted = true;
          break; // prima regola che matcha
        }

        // avanza lo stato al massimo UID visto (indipendentemente dal match)
        if (uid > lastSeenUid) lastSeenUid = uid;

        // (opzionale) se vuoi avanzare *solo* quando è stata applicata almeno una regola:
        // if (acted && uid > lastSeenUid) lastSeenUid = uid;
      }

      synchronized (statesLock) {
        replaceState(
            state
                .withLastProcessedUid(lastSeenUid)
                .withUpdatedAtEpochMs(System.currentTimeMillis()));
      }
      // Espelli i messaggi eliminati e chiudi (gestito da @Cleanup)
      folder.expunge();
      LangUtils.info(LOG, "Operazioni email completate.");
    } catch (Exception e) {
      LangUtils.err(LOG, "Errore durante l'elaborazione delle email: {}", LangUtils.exMsg(e));
    }
  }

  private void replaceState(@NonNull final State newState) {
    for (int i = 0; i < states.size(); i++) {
      val s = states.get(i);
      if (State.keyOf(s.getImapConfigName(), s.getFolder())
          .equals(State.keyOf(newState.getImapConfigName(), newState.getFolder()))) {
        states.set(i, newState);
        return;
      }
    }
    states.add(newState);
  }

  // Metodo per salvare la configurazione IMAP su JSON
  public void saveImaps() {
    val configFile = FileSystemUtils.getImapServersJson();
    try {
      FileSystemUtils.writeUtf8Atomic(configFile, GSON.toJson(imaps));
      LangUtils.info(LOG, "Salvata configurazione IMAP su {}", configFile);
    } catch (Exception e) {
      LangUtils.err(
          LOG,
          "Errore durante il salvataggio della configurazione IMAP in {}: {}",
          configFile,
          LangUtils.exMsg(e));
    }
  }

  // Metodo per salvare regole su JSON
  public void saveRules() {
    val rulesFile = FileSystemUtils.getRulesJson();
    try {
      FileSystemUtils.writeUtf8Atomic(rulesFile, GSON.toJson(rules));
      LangUtils.info(LOG, "Salvate {} regole su {}", rules.size(), rulesFile);
    } catch (Exception e) {
      LangUtils.err(
          LOG,
          "Errore durante il salvataggio delle regole in {}: {}",
          rulesFile,
          LangUtils.exMsg(e));
    }
  }

  private void saveStates() {
    val stateFile = FileSystemUtils.getProcessingStateJson();
    try {
      FileSystemUtils.writeUtf8Atomic(stateFile, GSON.toJson(states));
    } catch (Exception e) {
      LangUtils.err(
          LOG,
          "Errore durante il salvataggio del file di stato in {}: {}",
          stateFile,
          LangUtils.exMsg(e));
    }
  }
}
