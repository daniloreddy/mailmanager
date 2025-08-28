package org.danilorossi.mailmanager.helpers;

import java.io.IOException;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.logging.StreamHandler;
import lombok.NonNull;
import lombok.val;

public class LogConfigurator {

  protected static final String LOG_FILE = "application.log";
  protected static StreamHandler LOG_HANDLER;

  static {
    try {
      val logFile = FileSystemUtils.getConfigFile(LOG_FILE);
      LOG_HANDLER = new FileHandler(logFile.getAbsolutePath(), 1_000_000, 5, true); // 1MB, 5 file
    } catch (IOException ignored) {
      System.err.println(
          "Impossibile determinare la directory dell'eseguibile. Uso la directory corrente: "
              + ignored.getMessage());
      LOG_HANDLER = new ConsoleHandler();
    }
    LOG_HANDLER.setFormatter(new SimpleFormatter());
  }

  public static void configLog(@NonNull final Logger logger) {
    logger.addHandler(LOG_HANDLER);
    logger.setLevel(Level.parse(System.getProperty("LOG_LEVEL", "INFO")));
    logger.setUseParentHandlers(false); // Disabilita console logging predefinito
  }
}
