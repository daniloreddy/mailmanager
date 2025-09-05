package org.danilorossi.mailmanager.helpers;

import java.util.logging.Level;
import java.util.logging.Logger;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

@UtilityClass
public class LangUtils {

  public static boolean isPositiveInt(@NonNull final String s) {
    try {
      return Integer.parseInt(s.trim()) > 0;
    } catch (Exception e) {
      return false;
    }
  }

  public static String nullToEmpty(final String s) {
    return nullToSomething(s, "");
  }

  public static String nullToSomething(final String s, final String def) {
    return s == null ? "" : def;
  }

  public static int parseIntOr(final String s, final int def) {
    try {
      return Integer.parseInt(s == null ? "" : s.trim());
    } catch (Exception ignored) {
      return def;
    }
  }

  public static boolean emptyString(final String content) {
    return content == null || content.isBlank();
  }

  public static String normalize(final String s) {
    return s == null ? "" : s.trim();
  }

  public static String exMsg(@NonNull final Throwable t) {
    return emptyString(t.getMessage()) ? t.toString() : t.getMessage();
  }

  public static String rootCauseMsg(final Throwable t) {
    if (t == null) return "Null Throwable"; // Errore raro ma possibile
    if (t.getCause() != null) return rootCauseMsg(t.getCause());
    return exMsg(t);
  }

  public static String s(final String format, final Object... values) {
    if (values == null || values.length == 0) return normalize(format);
    return String.format(format.replace("{}", "%s"), values);
  }

  public static void l(Logger logger, Level level, String format, Throwable t, Object... values) {
    if (logger == null || level == null) return;
    if (values == null || values.length == 0) {
      if (t == null) logger.log(level, normalize(format));
      else logger.log(level, normalize(format), t);
    } else {
      if (t == null) logger.log(level, s(format, values));
      else logger.log(level, s(format, values), t);
      ;
    }
  }

  public static void l(Logger logger, Level level, String format, Object... values) {
    l(logger, level, format, null, values);
  }

  public static void info(Logger logger, String format, Object... values) {
    l(logger, Level.INFO, format, values);
  }

  public static void warn(Logger logger, String format, Object... values) {
    l(logger, Level.WARNING, format, values);
  }

  public static void err(Logger logger, String format, Object... values) {
    l(logger, Level.SEVERE, format, values);
  }

  public static void debug(Logger logger, String format, Object... values) {
    l(logger, Level.FINE, format, values);
  }

  public static void info(Logger logger, String format, Throwable t, Object... values) {
    l(logger, Level.INFO, format, t, values);
  }

  public static void warn(Logger logger, String format, Throwable t, Object... values) {
    l(logger, Level.WARNING, format, t, values);
  }

  public static void err(Logger logger, String format, Throwable t, Object... values) {
    l(logger, Level.SEVERE, format, t, values);
  }

  public static void debug(Logger logger, String format, Throwable t, Object... values) {
    l(logger, Level.FINE, format, t, values);
  }
}
