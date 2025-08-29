package org.danilorossi.mailmanager.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.val;

@Getter
@RequiredArgsConstructor
public enum ActionType {
  MOVE,
  COPY,
  DELETE,
  MARK_READ,
  MARK_UNREAD,
  FLAG,
  ADD_LABEL,
  REMOVE_LABEL,
  ARCHIVE,
  FORWARD, // richiede un target nel JSON regola
  STOP; // ferma l’elaborazione di ulteriori regole

  public static ActionType parse(final String s) {
    if (s == null) return null;
    val n = s.trim().replace('-', '_').toUpperCase();
    return switch (n) {
      case "MOVE" -> MOVE;
      case "COPY" -> COPY;
      case "DELETE", "DEL", "REMOVE" -> DELETE;
      case "MARK_READ", "READ" -> MARK_READ;
      case "MARK_UNREAD", "UNREAD" -> MARK_UNREAD;
      case "FLAG", "STAR" -> FLAG;
      case "ADD_LABEL", "LABEL_ADD" -> ADD_LABEL;
      case "REMOVE_LABEL", "LABEL_REMOVE" -> REMOVE_LABEL;
      case "ARCHIVE" -> ARCHIVE;
      case "FORWARD" -> FORWARD;
      case "STOP", "HALT", "BREAK" -> STOP;
      default -> null;
    };
  }
}
