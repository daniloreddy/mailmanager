package org.danilorossi.mailmanager;

import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.UIDFolder;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import lombok.Cleanup;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.java.Log;
import lombok.val;

import org.danilorossi.mailmanager.db.JsonDb;
import org.danilorossi.mailmanager.helpers.FileSystemUtils;
import org.danilorossi.mailmanager.helpers.LangUtils;
import org.danilorossi.mailmanager.helpers.LogConfigurator;
import org.danilorossi.mailmanager.helpers.MailUtils;
import org.danilorossi.mailmanager.helpers.SingleInstanceLock;
import org.danilorossi.mailmanager.helpers.SingleInstanceLock.AlreadyRunningException;
import org.danilorossi.mailmanager.model.ImapConfig;
import org.danilorossi.mailmanager.model.Rule;
import org.danilorossi.mailmanager.model.SpamAssassinConfig;
import org.danilorossi.mailmanager.model.State;
import org.danilorossi.mailmanager.spamassassin.SpamAssassinClient;
import org.danilorossi.mailmanager.tui.ConsoleTUI;

@Log
public class MailManager {

  // --- Persistenza / Repos
  private final JsonDb db = new JsonDb();

  static {
    LogConfigurator.configLog(log);
  }

  public static void main(String[] args) throws IOException {

    try (val ignored = SingleInstanceLock.acquire()) {
      LangUtils.info(log, "Lock acquisito su {} " , SingleInstanceLock.defaultLockPath());
      runMailManager(args);

    } catch (AlreadyRunningException busy) {
      // Se un'altra istanza è già attiva, usciamo silenziosamente
      LangUtils.warn(log, "MailManager già in esecuzione: {}", busy.getMessage());
      return;
    }
  }

  private static void runMailManager(String[] args) throws IOException {

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
      mailManager.loadSpamConfig();
    } catch (IOException __) {
      new ConsoleTUI(mailManager).start();
      return;
    }

    try {
      mailManager.loadImaps();
    } catch (IOException __) {
      new ConsoleTUI(mailManager).start();
      return;
    }

    mailManager.loadRules();

    // Modalità configurazione
    if (Arrays.asList(args).contains("-imap")
        || Arrays.asList(args).contains("-rules")
        || Arrays.asList(args).contains("-tui")) {
      new ConsoleTUI(mailManager).start();
      return;
    }

    // Elaborazione email
    mailManager.processEmails();
  }

  @Getter private List<ImapConfig> imaps = new ArrayList<>(); // Lista di configurazioni IMAP
  @Getter private List<Rule> rules = new ArrayList<>(); //  Lista di regole
  @Getter private SpamAssassinConfig spamConfig = SpamAssassinConfig.builder().build();

  /** Carica SpamAssassin; se il file non esiste, solleva IOException per aprire la TUI. */
  public void loadSpamConfig() throws IOException {
    val path = FileSystemUtils.getSpamAssassinJson();
    if (!Files.exists(path))
      throw new IOException("File di configurazione SPAM ASSASSIN non trovato.");
    this.spamConfig = db.spamd().load();
    LangUtils.info(log, "Caricata configurazione SPAM ASSASSIN da {}", path);
  }

  /**
   * Carica le configurazioni IMAP; se il file non esiste, solleva IOException per aprire la TUI.
   */
  public void loadImaps() throws IOException {
    val path = FileSystemUtils.getImapServersJson();
    if (!Files.exists(path)) throw new IOException("File di configurazione IMAP non trovato.");
    this.imaps = db.imaps().findAll();
    LangUtils.info(log, "Caricate {} configurazioni IMAP da {}", imaps.size(), path);
  }

  /** Carica le regole; se mancano, rimane lista vuota (comportamento attuale). */
  public void loadRules() {
    val path = FileSystemUtils.getRulesJson();
    if (!Files.exists(path)) {
      this.rules = new ArrayList<>();
      LangUtils.warn(log, "File regole non trovato in {}.", path);
      return;
    }
    this.rules = db.rules().findAll();
    LangUtils.info(log, "Caricate {} regole da {}", rules.size(), path);
  }

  // ===================== Salvataggi tramite JsonDb =====================

  /** Salva tutte le IMAP correnti. */
  public void saveImaps() {
    try {
      db.imaps().saveAll(imaps);
      LangUtils.info(
          log,
          "Salvata configurazione IMAP ({} elementi) su {}",
          imaps.size(),
          FileSystemUtils.getImapServersJson());
    } catch (Exception e) {
      LangUtils.err(log, "Errore nel salvataggio IMAP: {}", LangUtils.exMsg(e));
    }
  }

  /** Salva tutte le regole correnti. */
  public void saveRules() {
    try {
      db.rules().saveAll(rules);
      LangUtils.info(log, "Salvate {} regole su {}", rules.size(), FileSystemUtils.getRulesJson());
    } catch (Exception e) {
      LangUtils.err(log, "Errore nel salvataggio regole: {}", LangUtils.exMsg(e));
    }
  }

  /** Salva la configurazione SpamAssassin corrente. */
  public void saveSpamConfig() {
    try {
      db.spamd().save(spamConfig);
      LangUtils.info(
          log, "Salvata configurazione SPAM ASSASSIN su {}", FileSystemUtils.getSpamAssassinJson());
    } catch (Exception e) {
      LangUtils.err(log, "Errore nel salvataggio configurazione SPAM: {}", LangUtils.exMsg(e));
    }
  }

  // Recupera o crea/persisti lo state “per cartella” usando JsonDb.
  private State getOrCreateState(@NonNull String imapName, @NonNull String folderName) {
    val key = State.keyOf(imapName, folderName);
    // default “compatibile” con tua logica pregressa (lastProcessedUid=1L)
    val def =
        State.builder()
            .imapConfigName(imapName)
            .folder(folderName)
            .uidValidity(-1L)
            .lastProcessedUid(1L)
            .updatedAtEpochMs(System.currentTimeMillis())
            .build();

    val s = db.states().getOrDefault(key, def);
    // se era default (non presente su disco), persistilo subito
    if (s == def) db.states().put(def);
    return s;
  }

  // Sostituisce/persisti lo state aggiornato
  private void replaceState(@NonNull final State newState) {
    db.states().put(newState);
  }

  public void processEmails() {
    val maxThreads =
        !LangUtils.empty(System.getenv("DEV"))
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
      var state =
          getOrCreateState(imap.getName(), imap.getInboxFolder())
              .refreshValidity(currentUidValidity);
      replaceState(state); // persistiamo eventuale refresh

      val uidNext = uidFolder.getUIDNext();
      if (uidNext <= 0) {
        LangUtils.info(log, "La casella sembra vuota (UIDNEXT <= 0).");
        replaceState(
            state
                .withUidValidity(currentUidValidity)
                .withUpdatedAtEpochMs(System.currentTimeMillis()));
        return;
      }

      // range da leggere: dall’ultimo processato + 1 fino a UIDNEXT-1
      val startUid = (state.getLastProcessedUid() > 0) ? state.getLastProcessedUid() + 1 : 1;
      long endUid = uidNext - 1;
      if (startUid > endUid) {
        LangUtils.info(log, "Nessun nuovo messaggio.");
        replaceState(
            state
                .withUidValidity(currentUidValidity)
                .withUpdatedAtEpochMs(System.currentTimeMillis()));
        return;
      }

      val messages = uidFolder.getMessagesByUID(startUid, endUid);

      // Prefetch per ridurre roundtrip
      val fp = new jakarta.mail.FetchProfile();
      fp.add(UIDFolder.FetchProfileItem.UID);
      fp.add(jakarta.mail.FetchProfile.Item.ENVELOPE);
      fp.add(jakarta.mail.FetchProfile.Item.FLAGS);
      fp.add(jakarta.mail.FetchProfile.Item.CONTENT_INFO); // opzionale

      // Header specifici che spesso servono
      fp.add("Subject");
      fp.add("From");
      fp.add("To");
      fp.add("Date");
      fp.add("Message-ID");
      fp.add("List-Id");
      fp.add("Authentication-Results");
      fp.add("Received-SPF");
      folder.fetch(messages, fp);

      LangUtils.info(log, "Trovati {} messaggi (UID {}..{}).", messages.length, startUid, endUid);

      long lastSeenUid = state.getLastProcessedUid();
      // il flag indica se qualcosa è stato eliminato (per espulsione a fine ciclo)
      boolean anyDeleted = false;

      // Client SPAM ASSASSIN (opzionale)
      SpamAssassinClient spamClient = null;
      if (imap.isUseSpamAssassin()) {
        try {
          spamClient = SpamAssassinClient.newFromConfig(spamConfig);
          LangUtils.info(
              log, "SpamAssassin attivo su {}:{}", spamConfig.getHost(), spamConfig.getPort());
        } catch (Exception se) {
          LangUtils.err(log, "SpamAssassin non disponibile: {}", LangUtils.exMsg(se));
          spamClient = null;
        }
      }

      for (val message : messages) {
        long uid = uidFolder.getUID(message);
        if (uid <= 0) continue;

        // controllo stato (double-check: uidValidity e monotonia)
        if (!state.shouldProcess(uid, currentUidValidity)) {
          lastSeenUid = Math.max(lastSeenUid, uid); // tieni comunque traccia del massimo visto
          continue;
        }

        // il controllo SPAMASSIN va fatto prima di valutare le regole
        if (spamClient != null) {
          try {
            val check = spamClient.check(message); // recupera header e body
            LangUtils.info(
                log,
                "SpamAssassin check: UID:{} SPAM:{} SCORE:{} SOGLIA:{}",
                uid,
                check.isSpam(),
                check.getScore(),
                check.getThreshold());
            if (check.isSpam()) {
              switch (imap.getSpamAction()) {
                case DELETE -> {
                  message.setFlag(jakarta.mail.Flags.Flag.DELETED, true);
                  anyDeleted = true;
                  LangUtils.info(log, "UID {} marcato SPAM: azione DELETE.", uid);
                }
                case MOVE -> {
                  try {
                    @Cleanup
                    val dst = MailUtils.ensureFolderExistsAndOpen(store, imap.getSpamFolder());
                    folder.copyMessages(new Message[] {message}, dst);
                    message.setFlag(
                        jakarta.mail.Flags.Flag.DELETED, true); // opzionale: rimuovi dall’Inbox
                    anyDeleted = true;
                    LangUtils.info(
                        log, "UID {} marcato SPAM: spostato in '{}'.", uid, imap.getSpamFolder());
                  } catch (Exception moveEx) {
                    LangUtils.err(
                        log,
                        "Errore spostamento SPAM UID {} in '{}': {}",
                        uid,
                        imap.getSpamFolder(),
                        LangUtils.exMsg(moveEx));
                    // fallback sicuro: marca come letto per non riprocessarlo all’infinito
                    message.setFlag(jakarta.mail.Flags.Flag.SEEN, true);
                  }
                }
                case MARK_AS_READ -> {
                  message.setFlag(jakarta.mail.Flags.Flag.SEEN, true);
                  LangUtils.info(log, "UID {} marcato SPAM: azione MARK_AS_READ.", uid);
                }
              }
              // avanza lo stato
              lastSeenUid = Math.max(lastSeenUid, uid);
              continue; // passa al messaggio successivo
            }
          } catch (Exception se) {
            // Non bloccare l’elaborazione: logga e continua senza SpamAssassin
            LangUtils.err(log, "Errore SpamAssassin su UID {}: {}", uid, LangUtils.exMsg(se));
          }
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
        lastSeenUid = Math.max(lastSeenUid, uid);

        // (opzionale) se vuoi avanzare *solo* quando è stata applicata almeno una regola:
        // if (acted && uid > lastSeenUid) lastSeenUid = uid;
      }

      replaceState(
          state.withLastProcessedUid(lastSeenUid).withUpdatedAtEpochMs(System.currentTimeMillis()));

      // Espelli i messaggi eliminati e chiudi (gestito da @Cleanup)
      if (anyDeleted) {
        folder.expunge(); // chiama expunge solo se hai marcato qualcosa
        LangUtils.info(log, "Expunge eseguito: messaggi spam rimossi.");
      }
      LangUtils.info(log, "Operazioni email completate.");
    } catch (Exception e) {
      LangUtils.err(log, "Errore durante l'elaborazione delle email: {}", LangUtils.exMsg(e));
    }
  }
}
