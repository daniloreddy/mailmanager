package org.danilorossi.mailmanager.db;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;
import lombok.NonNull;
import lombok.Synchronized;
import lombok.extern.java.Log;
import lombok.val;
import org.danilorossi.mailmanager.helpers.FileSystemUtils;
import org.danilorossi.mailmanager.helpers.LangUtils;
import org.danilorossi.mailmanager.helpers.LogConfigurator;
import org.danilorossi.mailmanager.model.ConditionOperator;
import org.danilorossi.mailmanager.model.ConditionSubject;
import org.danilorossi.mailmanager.model.ImapConfig;
import org.danilorossi.mailmanager.model.Rule;
import org.danilorossi.mailmanager.model.SpamAssassinConfig;
import org.danilorossi.mailmanager.model.State;

/**
 * Layer di persistenza basato su file JSON. Ogni "repository" gestisce un tipo di dato
 * dell'applicazione (SpamAssassinConfig, ImapConfig, Rule, State).
 *
 * <p>Utilizza i path standard di FileSystemUtils e le utility di LangUtils + LogConfigurator per
 * loggare gli errori.
 *
 * <p>Thread-safety: per ogni repository esiste un lock statico e tutti i metodi pubblici che
 * accedono al file sono annotati con @Synchronized("<LOCK_NAME>").
 */
@Log
public class JsonDb implements AutoCloseable {

  private static final Gson GSON =
      new GsonBuilder().setPrettyPrinting().serializeNulls().disableHtmlEscaping().create();

  static {
    LogConfigurator.configLog(log);
  }

  public static Gson gson() {
    return GSON;
  }

  public JsonDb() {}

  // ---------- Metodi comuni (chiamati dentro sezioni sincronizzate via @Synchronized) ----------

  /**
   * Legge un file JSON e deserializza in un oggetto del tipo specificato. Se il file non esiste o
   * fallisce la lettura, viene restituito il valore di default. Variante con Class<T> → nessun cast
   * necessario.
   */
  private static <T> T readOrDefault(
      @NonNull final Path file, @NonNull final Class<T> type, @NonNull final Supplier<T> def) {
    try {
      if (!Files.exists(file)) return def.get();
      val json = FileSystemUtils.readUtf8(file);
      val obj = gson().fromJson(json, type);
      return obj != null ? obj : def.get();
    } catch (RuntimeException ex) {
      LangUtils.warn(log, "Errore lettura {}: {}", ex, file, LangUtils.rootCauseMsg(ex));
      return def.get();
    }
  }

  /**
   * Legge un file JSON e deserializza in un oggetto generico (liste, mappe, ecc.). Se il file non
   * esiste o fallisce la lettura, viene restituito il valore di default. Variante con Type → serve
   * un cast, nascosto qui con @SuppressWarnings.
   */
  @SuppressWarnings("unchecked")
  private static <T> T readOrDefault(
      @NonNull final Path file, @NonNull final Type type, @NonNull final Supplier<T> def) {
    try {
      if (!Files.exists(file)) return def.get();
      val json = FileSystemUtils.readUtf8(file);
      Object obj = gson().fromJson(json, type);
      return obj != null ? (T) obj : def.get();
    } catch (RuntimeException ex) {
      LangUtils.warn(log, "Errore lettura {}: {}", ex, file, LangUtils.rootCauseMsg(ex));
      return def.get();
    }
  }

  /** Scrive un oggetto su file JSON in maniera atomica (tmp + move). */
  private static <T> void writeAtomic(@NonNull final Path file, @NonNull final T payload) {
    try {
      val json = gson().toJson(payload);
      FileSystemUtils.writeUtf8Atomic(file, json);
    } catch (RuntimeException ex) {
      LangUtils.err(log, "Errore scrittura {}: {}", ex, file, LangUtils.rootCauseMsg(ex));
      throw ex;
    }
  }

  // ---------- Repositories pubblici ----------
  public SpamAssassinConfigStore spamd() {
    return new SpamAssassinConfigStore();
  }

  public ImapConfigRepository imaps() {
    return new ImapConfigRepository();
  }

  public RuleRepository rules() {
    return new RuleRepository();
  }

  public StateStore states() {
    return new StateStore();
  }

  @Override
  public void close() {
    /* niente da chiudere */
  }

  // ===================================== STORES =====================================

  /** File singolo: configurazione globale di SpamAssassin. */
  public static class SpamAssassinConfigStore {
    /** Lock statico condiviso per lo stesso file */
    private static final Object SPAMD_LOCK = new Object();

    private Path file() {
      return FileSystemUtils.getSpamAssassinJson();
    }

    @Synchronized("SPAMD_LOCK")
    public SpamAssassinConfig load() {
      return readOrDefault(file(), SpamAssassinConfig.class, SpamAssassinConfig::new);
    }

    @Synchronized("SPAMD_LOCK")
    public void save(@NonNull SpamAssassinConfig cfg) {
      writeAtomic(file(), cfg);
    }
  }

  /**
   * Mappa di stati di elaborazione IMAP. La chiave è quella generata da State.key() →
   * "imapConfig:folder".
   */
  public static class StateStore {
    private static final Object STATES_LOCK = new Object();
    private static final Type MAP_STATE = new TypeToken<Map<String, State>>() {}.getType();

    private Path file() {
      return FileSystemUtils.getProcessingStateJson();
    }

    @Synchronized("STATES_LOCK")
    public Map<String, State> loadAll() {
      return readOrDefault(file(), MAP_STATE, () -> Collections.<String, State>emptyMap());
    }

    @Synchronized("STATES_LOCK")
    public State getOrDefault(@NonNull final String key, @NonNull final State def) {
      return readOrDefault(file(), MAP_STATE, () -> Collections.<String, State>emptyMap())
          .getOrDefault(LangUtils.nz(key), def);
    }

    @Synchronized("STATES_LOCK")
    public void put(@NonNull final State s) {
      val all = new LinkedHashMap<>(readOrDefault(file(), MAP_STATE, Collections::emptyMap));
      all.put(s.key(), s);
      writeAtomic(file(), all);
    }

    @Synchronized("STATES_LOCK")
    public void putAll(@NonNull final Map<String, State> map) {
      val all = new LinkedHashMap<>(readOrDefault(file(), MAP_STATE, Collections::emptyMap));
      all.putAll(map);
      writeAtomic(file(), all);
    }

    @Synchronized("STATES_LOCK")
    public boolean delete(@NonNull final String key) {
      val all = new LinkedHashMap<>(readOrDefault(file(), MAP_STATE, Collections::emptyMap));
      if (all.remove(LangUtils.nz(key)) != null) {
        writeAtomic(file(), all);
        return true;
      }
      return false;
    }
  }

  /**
   * Lista di configurazioni IMAP. La chiave naturale è il name, se presente, altrimenti
   * host:port:username.
   */
  public static class ImapConfigRepository {
    private static final Object IMAPS_LOCK = new Object();
    private static final Type LIST_IMAP = new TypeToken<List<ImapConfig>>() {}.getType();

    private Path file() {
      return FileSystemUtils.getImapServersJson();
    }

    @Synchronized("IMAPS_LOCK")
    public List<ImapConfig> findAll() {
      return readOrDefault(file(), LIST_IMAP, List::of);
    }

    /** Inserisce o aggiorna la configurazione in base alla chiave. */
    @Synchronized("IMAPS_LOCK")
    public ImapConfig upsert(@NonNull final ImapConfig cfg) {
      // opzionale: validazione fail-fast (non blocca, ma logga)
      try {
        cfg.validate();
      } catch (Exception e) {
        LangUtils.warn(log, "IMAP non valido: {}", e, LangUtils.rootCauseMsg(e));
      }
      val key = keyOf(cfg);
      val all =
          new ArrayList<>(
              readOrDefault(file(), LIST_IMAP, () -> Collections.<ImapConfig>emptyList()));
      val idx = indexOf(all, key);
      if (idx >= 0) all.set(idx, cfg);
      else all.add(cfg);
      writeAtomic(file(), all);
      return cfg;
    }

    /** Cancella una configurazione dato il nome o la chiave naturale. */
    @Synchronized("IMAPS_LOCK")
    public boolean delete(@NonNull final String nameOrKey) {
      val k = LangUtils.nz(nameOrKey);
      val all =
          new ArrayList<>(
              readOrDefault(file(), LIST_IMAP, () -> Collections.<ImapConfig>emptyList()));
      int before = all.size();
      all.removeIf(c -> keyOf(c).equalsIgnoreCase(k));
      if (all.size() != before) {
        writeAtomic(file(), all);
        return true;
      }
      return false;
    }

    /** Trova una configurazione dato il nome o la chiave naturale. */
    @Synchronized("IMAPS_LOCK")
    public ImapConfig findByKey(@NonNull final String nameOrKey) {
      val k = LangUtils.nz(nameOrKey);
      return readOrDefault(file(), LIST_IMAP, () -> Collections.<ImapConfig>emptyList()).stream()
          .filter(c -> keyOf(c).equalsIgnoreCase(k))
          .findFirst()
          .orElse(null);
    }

    /** Salva l'intera lista (utile per import/export). */
    @Synchronized("IMAPS_LOCK")
    public void saveAll(@NonNull final List<ImapConfig> list) {
      writeAtomic(file(), new ArrayList<>(list));
    }

    public static String keyOf(@NonNull final ImapConfig c) {
      if (!LangUtils.empty(c.getName())) return LangUtils.nz(c.getName());
      return LangUtils.nz(c.getHost())
          + ":"
          + LangUtils.nz(c.getPort())
          + ":"
          + LangUtils.nz(c.getUsername());
    }

    private static int indexOf(@NonNull final List<ImapConfig> list, final String key) {
      for (int i = 0; i < list.size(); i++) if (keyOf(list.get(i)).equalsIgnoreCase(key)) return i;
      return -1;
    }
  }

  /**
   * Lista di regole. La chiave viene costruita concatenando i campi principali per garantire upsert
   * idempotente.
   */
  public static class RuleRepository {
    private static final Object RULES_LOCK = new Object();
    private static final Type LIST_RULE = new TypeToken<List<Rule>>() {}.getType();

    private Path file() {
      return FileSystemUtils.getRulesJson();
    }

    @Synchronized("RULES_LOCK")
    public List<Rule> findAll() {
      return readOrDefault(file(), LIST_RULE, List::of);
    }

    @Synchronized("RULES_LOCK")
    public List<Rule> findByImap(@NonNull final String imapConfigName) {
      val n = LangUtils.nz(imapConfigName);
      return readOrDefault(file(), LIST_RULE, () -> Collections.<Rule>emptyList()).stream()
          .filter(r -> n.equalsIgnoreCase(r.getImapConfigName()))
          .toList();
    }

    /** Inserisce o aggiorna una regola. */
    @Synchronized("RULES_LOCK")
    public Rule upsert(@NonNull final Rule r) {
      val key = keyOf(r);
      val all =
          new ArrayList<>(readOrDefault(file(), LIST_RULE, () -> Collections.<Rule>emptyList()));
      val idx = indexOf(all, key);
      if (idx >= 0) all.set(idx, r);
      else all.add(r);
      writeAtomic(file(), all);
      return r;
    }

    /** Inserisce o aggiorna una lista di regole. */
    @Synchronized("RULES_LOCK")
    public int upsertAll(@NonNull final List<Rule> rules) {
      val map = new LinkedHashMap<String, Rule>();
      for (val r : readOrDefault(file(), LIST_RULE, () -> Collections.<Rule>emptyList()))
        map.put(keyOf(r), r);
      for (val r : rules) map.put(keyOf(r), r);
      writeAtomic(file(), new ArrayList<>(map.values()));
      LangUtils.info(log, "Regole salvate: {} elementi", rules.size());
      return rules.size();
    }

    @Synchronized("RULES_LOCK")
    public boolean delete(@NonNull final Rule r) {
      return deleteByKey(keyOf(r));
    }

    @Synchronized("RULES_LOCK")
    public boolean deleteByKey(@NonNull final String key) {
      val all =
          new ArrayList<>(readOrDefault(file(), LIST_RULE, () -> Collections.<Rule>emptyList()));
      int before = all.size();
      all.removeIf(x -> keyOf(x).equals(key));
      if (all.size() != before) {
        writeAtomic(file(), all);
        return true;
      }
      return false;
    }

    @Synchronized("RULES_LOCK")
    public void saveAll(@NonNull final List<Rule> list) {
      writeAtomic(file(), new ArrayList<>(list));
    }

    private static String keyOf(@NonNull final Rule r) {
      return String.join(
          "|",
          LangUtils.nz(r.getImapConfigName()),
          String.valueOf(r.getActionType()),
          String.valueOf(r.getConditionOperator()),
          String.valueOf(r.getConditionSubject()),
          LangUtils.nz(r.getConditionValue()),
          LangUtils.nz(r.getDestValue()),
          String.valueOf(r.isCaseSensitive()));
    }

    private static int indexOf(@NonNull final List<Rule> list, final String key) {
      for (int i = 0; i < list.size(); i++) if (keyOf(list.get(i)).equals(key)) return i;
      return -1;
    }
  }

  // ===================================== DEMO USAGE =====================================
  public static void main(String[] args) {

    try (val db = new JsonDb()) {

      // SpamAssassin
      val spam = db.spamd().load();
      db.spamd()
          .save(
              SpamAssassinConfig.builder()
                  .enabled(true)
                  .host(spam.getHost())
                  .port(spam.getPort())
                  .build());

      // IMAP config
      db.imaps()
          .upsert(
              ImapConfig.builder()
                  .name("Personal")
                  .host("imap.example.com")
                  .port("993")
                  .username("me@example.com")
                  .password("secret")
                  .build());

      // Rule
      db.rules()
          .upsert(
              Rule.builder()
                  .imapConfigName("Personal")
                  .conditionSubject(ConditionSubject.SUBJECT)
                  .conditionOperator(ConditionOperator.CONTAINS)
                  .conditionValue("Diablo")
                  .actionType(org.danilorossi.mailmanager.model.ActionType.MARK_READ)
                  .caseSensitive(false)
                  .build());

      // State
      val st =
          State.builder()
              .imapConfigName("Personal")
              .folder("INBOX")
              .uidValidity(12345L)
              .lastProcessedUid(67890L)
              .updatedAtEpochMs(System.currentTimeMillis())
              .build();
      db.states().put(st);
    }
  }
}
