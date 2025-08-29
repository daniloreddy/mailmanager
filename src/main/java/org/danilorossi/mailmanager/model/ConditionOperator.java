package org.danilorossi.mailmanager.model;

import java.util.regex.Pattern;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.val;

@Getter
@RequiredArgsConstructor
public enum ConditionOperator {
  EQUALS,
  NOT_EQUALS,
  CONTAINS,
  NOT_CONTAINS,
  STARTS_WITH,
  ENDS_WITH,
  REGEX; // espressione regolare sul campo

  public static ConditionOperator parse(final String s) {
    if (s == null) return null;
    val n = s.trim().replace('-', '_').toUpperCase();
    return switch (n) {
      case "EQUALS", "EQ", "==" -> EQUALS;
      case "NOT_EQUALS", "NE", "!=", "<>" -> NOT_EQUALS;
      case "CONTAINS", "HAS", "~=" -> CONTAINS;
      case "NOT_CONTAINS", "NOT_HAS", "!~" -> NOT_CONTAINS;
      case "STARTS_WITH", "SW", "^=" -> STARTS_WITH;
      case "ENDS_WITH", "EW", "$=" -> ENDS_WITH;
      case "REGEX", "MATCHES" -> REGEX;
      default -> null;
    };
  }

  /** Confronto pronto all’uso; se caseSensitive=false normalizza in lower-case. */
  public boolean test(final String _left, final String _right, final boolean caseSensitive) {
    val left = _left == null ? "" : _left;
    val right = _right == null ? "" : _right;
    val a = caseSensitive ? left : left.toLowerCase();
    val b = caseSensitive ? right : right.toLowerCase();

    return switch (this) {
      case EQUALS -> a.equals(b);
      case NOT_EQUALS -> !a.equals(b);
      case CONTAINS -> a.contains(b);
      case NOT_CONTAINS -> !a.contains(b);
      case STARTS_WITH -> a.startsWith(b);
      case ENDS_WITH -> a.endsWith(b);
      case REGEX -> {
        val flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        yield Pattern.compile(right, flags).matcher(left).find();
      }
    };
  }
}
