package org.danilorossi.mailmanager.helpers;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.*;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import lombok.val;

@UtilityClass
public class FileSystemUtils {

  private static final String PROP_HOME = "mailmanager.home"; // override opzionale
  private static final String DIR_DATA = "data";

  // Nomi standard dei 3 file di configurazione
  private static final String SPASS_JSON = "spam-assassin.json";
  private static final String SERVERS_JSON = "imap-servers.json";
  private static final String RULES_JSON = "rules.json";
  private static final String OFFSETS_JSON = "processing-state.json";

  /** Nome predefinito del file di lock sotto data/. */
  public static final String LOCK_FILENAME = "mailmanager.lock";

  /** Restituisce la cartella "data" dove salvare i file di config. */
  public static Path getDataDir() {
    // 1) Sviluppo: ENV DEV
    val dev = System.getenv("DEV");
    if (!LangUtils.empty(dev)) return ensureDir(cwd().resolve(DIR_DATA));

    // 2) Override esplicito: -Dmailmanager.home=/percorso/custom
    val override = System.getProperty(PROP_HOME);
    if (!LangUtils.empty(override)) return ensureDir(normalize(Paths.get(override)));

    // 3) Deduci dalla posizione dell'app (jar o classes/)
    try {
      val base = resolveExecutableBaseDir(); // jar -> parent; classes/ -> parent di classes
      if (base != null) return ensureDir(base.resolve(DIR_DATA));
    } catch (Exception __) {
      // fallback sotto
    }

    // 4) Fallback: CWD/data
    return ensureDir(cwd().resolve(DIR_DATA));
  }

  /** Path a imap-servers.json. */
  public static Path getSpamAssassinJson() {
    return getDataDir().resolve(SPASS_JSON);
  }

  /** Path a imap-servers.json. */
  public static Path getImapServersJson() {
    return getDataDir().resolve(SERVERS_JSON);
  }

  /** Path a rules.json. */
  public static Path getRulesJson() {
    return getDataDir().resolve(RULES_JSON);
  }

  /** Path a uids.json. */
  public static Path getProcessingStateJson() {
    return getDataDir().resolve(OFFSETS_JSON);
  }

  
  public static Path getLockFilePath() {
    return getDataDir().resolve(LOCK_FILENAME);
  }

  /** Restituisce un file generico sotto data/. */
  public static Path getDataPath(@NonNull String fileName) {
    return getDataDir().resolve(fileName);
  }

  /** Retro-compatibilità: File da path sotto data/. */
  public static File getDataFile(@NonNull String fileName) {
    return getDataPath(fileName).toFile();
  }

  private static Path cwd() {
    return normalize(Paths.get("."));
  }

  private static Path normalize(@NonNull final Path p) {
    return p.toAbsolutePath().normalize();
  }

  private static Path ensureDir(@NonNull final Path dir) {
    try {
      Files.createDirectories(dir);
      if (!Files.isDirectory(dir))
        throw new IOException(LangUtils.s("Path exists but is not a directory: {}", dir));
      return dir;
    } catch (IOException e) {
      throw new RuntimeException(LangUtils.s("Cannot create directory: {}", dir), e);
    }
  }

  /**
   * Base dell'eseguibile: jar ⇒ parent; .../target/classes ⇒ parent di classes; altrimenti
   * location.
   */
  private static Path resolveExecutableBaseDir() throws URISyntaxException {
    val cs = FileSystemUtils.class.getProtectionDomain().getCodeSource();
    if (cs == null) return null;
    val loc = normalize(Paths.get(cs.getLocation().toURI()));
    if (Files.isRegularFile(loc)) return normalize(loc.getParent()); // jar
    val name = loc.getFileName() != null ? loc.getFileName().toString() : "";
    if ("classes".equals(name)) return normalize(loc.getParent()); // .../target
    return loc;
  }

  /** Scrive testo UTF-8 in modo atomico (tmp nella stessa dir + move). */
  public static void writeUtf8Atomic(@NonNull final Path _target, @NonNull final String content) {
    val target = normalize(_target);
    ensureDir(target.getParent());
    try {
      val tmp =
          Files.createTempFile(target.getParent(), target.getFileName().toString() + "-", ".tmp");
      try {
        Files.writeString(tmp, content, StandardCharsets.UTF_8, CREATE, TRUNCATE_EXISTING, WRITE);
        try {
          Files.move(tmp, target, REPLACE_EXISTING, ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException __) {
          Files.move(tmp, target, REPLACE_EXISTING);
        }
      } finally {
        try {
          Files.deleteIfExists(tmp);
        } catch (IOException __) {
        }
      }
    } catch (IOException e) {
      throw new RuntimeException(LangUtils.s("Cannot write file: ", target), e);
    }
  }

  /** Legge testo UTF-8 (eccezione runtime semplificata). */
  public static String readUtf8(@NonNull final Path file) {
    try {
      return Files.readString(normalize(file), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new RuntimeException(LangUtils.s("Cannot read file: ", file), e);
    }
  }
}
