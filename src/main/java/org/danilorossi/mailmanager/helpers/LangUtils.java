package org.danilorossi.mailmanager.helpers;

public class LangUtils {

  private LangUtils() {}

  public static boolean emptyString(final String content) {
    return content == null || content.length() == 0 || content.trim().isEmpty();
  }
}
