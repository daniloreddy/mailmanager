package org.danilorossi.mailmanager.helpers;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import lombok.NonNull;
import lombok.val;

public class FileSystemUtils {

  private static final String PROP_HOME = "mailmanager.home"; // override opzionale

  // Costruisce il percorso completo per un file di configurazione
  // public static File getConfigFile(@NonNull final String fileName) {
  //   return new File(getExecutableDirectory(), fileName);
  // }

  // Ottiene la directory dell'eseguibile
  // public static File getExecutableDirectory() {
  //   File dir;
  //   // in caso stessi lanciando il progeamma dall'IDE setto la variabile __DEV__ ceh fissa il
  // path
  //   // di origine
  //   val dev = System.getProperty("DEV", "");
  //   if ("__DEV__".equals(dev)) dir = new File(".", "datadada");
  //   else
  //     try {
  //       val jarUrl = FileSystemUtils.class.getProtectionDomain().getCodeSource().getLocation();
  //       val jarFile = new File(jarUrl.toURI());
  //       dir = new File(jarFile.getParentFile(), "data");
  //     } catch (Exception e) {
  //       dir = new File(".", "data");
  //     }
  //   if (!dir.exists()) dir.mkdirs();
  //   return dir;
  // }

  private FileSystemUtils() {}

  /** Restituisce la cartella "data" dove salvare i file di config. */
  public static Path getConfigDir() {
    // 1) Ovberride esplicito: -DDEV=_DEV_
    val dev = System.getProperty("DEV", "");
    if (LangUtils.emptyString(dev)) {
      // 4) Fallback: ./data rispetto alla working dir
      val p = Paths.get(".").toAbsolutePath().normalize().resolve("data");
      ensureDir(p);
      return p;
    }

    // 2) Override esplicito: -Dmailmanager.home=/percorso/custom
    val override = System.getProperty(PROP_HOME);
    if (override != null && !override.isBlank()) {
      val p = Paths.get(override).toAbsolutePath().normalize();
      ensureDir(p);
      return p;
    }

    // 3) Prova a dedurre la cartella dell'app (jar o classes/)
    try {
      val cs = FileSystemUtils.class.getProtectionDomain().getCodeSource();
      if (cs != null) {
        val uri = cs.getLocation().toURI(); // file:/.../app.jar oppure .../target/classes/
        val loc = Paths.get(uri);
        val base =
            Files.isRegularFile(loc) ? loc.getParent() : loc; // jar -> parent, classes/ -> itself
        val data = base.resolve("data");
        ensureDir(data);
        return data;
      }
    } catch (Exception ignored) {
      // fallback sotto
    }

    // 4) Fallback: ./data rispetto alla working dir
    val data = Paths.get(".").toAbsolutePath().normalize().resolve("data");
    ensureDir(data);
    return data;
  }

  /** Restituisce il file di config (Path). */
  public static Path getConfigPath(@NonNull String fileName) {
    return getConfigDir().resolve(fileName);
  }

  /** Restituisce il file di config (File) per retrocompatibilità. */
  public static File getConfigFile(@NonNull String fileName) {
    return getConfigPath(fileName).toFile();
  }

  private static void ensureDir(@NonNull final Path dir) {
    try {
      Files.createDirectories(dir);
    } catch (IOException e) {
      throw new RuntimeException("Impossibile creare la directory di configurazione: " + dir, e);
    }
  }
}
