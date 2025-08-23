package org.danilorossi.mailmanager.helpers;

import java.io.File;

import lombok.NonNull;
import lombok.val;

public class FileSystemUtils {
  // Costruisce il percorso completo per un file di configurazione
  public static File getConfigFile(@NonNull final String fileName) {
    return new File(getExecutableDirectory(), fileName);
  }

  // Ottiene la directory dell'eseguibile
  public static File getExecutableDirectory() {
    File dir;
    // in caso stessi lanciando il progeamma dall'IDE setto la variabile __DEV__ ceh fissa il path
    // di origine
    val dev = System.getProperty("DEV", "__DEV__");
    if ("__DEV__".equals(dev)) dir = new File(".", "data");
    else
      try {
        val jarUrl = FileSystemUtils.class.getProtectionDomain().getCodeSource().getLocation();
        val jarFile = new File(jarUrl.toURI());
        dir = new File(jarFile.getParentFile(), "data");
      } catch (Exception e) {
        dir = new File(".", "data");
      }
    if (!dir.exists()) dir.mkdir();
    return dir;
  }
}
