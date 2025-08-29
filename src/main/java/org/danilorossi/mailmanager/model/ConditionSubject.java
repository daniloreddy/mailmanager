package org.danilorossi.mailmanager.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.val;

@Getter
@RequiredArgsConstructor
public enum ConditionSubject {
  SUBJECT, // oggetto
  FROM,
  TO,
  CC,
  BCC, // in italiano spesso scritto “CCN”
  MESSAGE; // corpo (testo estratto)

  public static ConditionSubject parse(final String s) {
    if (s == null) return null;
    val n = s.trim().replace('-', '_').toUpperCase();
    return switch (n) {
      case "SUBJECT", "OGGETTO" -> SUBJECT;
      case "FROM", "DA" -> FROM;
      case "TO", "A" -> TO;
      case "CC", "CARBON_COPY" -> CC;
      case "BCC", "CCN", "BLIND_CARBON_COPY" -> BCC;
      case "MESSAGE", "BODY", "TESTO" -> MESSAGE;
      default -> null;
    };
  }
}
