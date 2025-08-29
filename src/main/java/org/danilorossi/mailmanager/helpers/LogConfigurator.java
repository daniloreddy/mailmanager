package org.danilorossi.mailmanager.helpers;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import lombok.val;

@UtilityClass
public class LogConfigurator {

  protected static final String LOG_FILE = "application.log";
  private static final Handler LOG_HANDLER;
  private static final AtomicBoolean ONCE = new AtomicBoolean(false);

  static {
    Handler handler;
    try {
      handler =
          new FileHandler(
              FileSystemUtils.getDataFile(LOG_FILE).getAbsolutePath(),
              1_000_000,
              5,
              true); // 1MB, 5 file
    } catch (IOException ex) {
      System.err.println(
          LangUtils.s(
              "Impossibile determinare la directory dell'eseguibile. Uso la directory corrente: {}",
              LangUtils.exMsg(ex)));
      handler = new ConsoleHandler();
    }
    handler.setFormatter(new SimpleFormatter());
    try {
      handler.setEncoding("UTF-8");
    } catch (Exception __) {
    }

    LOG_HANDLER = handler;
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  try {
                    LOG_HANDLER.flush();
                    LOG_HANDLER.close();
                  } catch (Exception ignored) {
                  }
                }));
  }

  public static void configLog(@NonNull final Logger logger) {
    // rimuove eventuali handler già presenti
    for (val h : logger.getHandlers()) logger.removeHandler(h);
    logger.addHandler(LOG_HANDLER);
    logger.setLevel(resolveLogLevel(System.getProperty("LOG_LEVEL", "INFO")));
    logger.setUseParentHandlers(false); // Disabilita console logging predefinito
    if (ONCE.compareAndSet(false, true))
      LogManager.getLogManager().getLogger("").setLevel(logger.getLevel());
  }

  private static Level resolveLogLevel(@NonNull final String levelName) {
    // Mappa friendly name → JUL Level
    val map = new HashMap<String, Level>();
    map.put("DEBUG", Level.FINE);
    map.put("TRACE", Level.FINEST);

    if (map.containsKey(levelName.toUpperCase())) return map.get(levelName.toUpperCase());

    try {
      return Level.parse(levelName.toUpperCase());
    } catch (IllegalArgumentException e) {
      return Level.INFO;
    }
  }

  /** opzionale: convoglia anche System.out e System.err nel log */
  public static void redirectSystemStreams(@NonNull final Logger logger) {
    System.setOut(
        new PrintStream(
            LoggingOutputStream.builder().logger(logger).level(Level.INFO).build(), true));
    System.setErr(
        new PrintStream(
            LoggingOutputStream.builder().logger(logger).level(Level.SEVERE).build(), true));
  }

  /** piccolo helper per stream redirection */
  @NoArgsConstructor
  @AllArgsConstructor
  @Builder
  private static class LoggingOutputStream extends OutputStream {
    @NonNull private Logger logger;
    @NonNull private Level level;
    private final StringBuilder buffer = new StringBuilder();

    @Override
    public void write(final int b) {
      if (b == '\n') {
        flush();
      } else {
        buffer.append((char) b);
      }
    }

    @Override
    public void flush() {
      if (buffer.length() > 0) {
        logger.log(level, buffer.toString());
        buffer.setLength(0);
        try {
          super.flush();
        } catch (Exception __) {
        }
      }
    }
  }
}
