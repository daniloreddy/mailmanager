package org.danilorossi.mailmanager.spamassassin;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
public enum Command {
  CHECK("CHECK"),
  SYMBOLS("SYMBOLS");

  @Getter private final String verb;
}
