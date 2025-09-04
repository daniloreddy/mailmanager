package org.danilorossi.mailmanager.spamassassin;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class SymbolsResult {
  boolean ok;
  boolean isSpam;
  Double score;
  Double threshold;
  String symbolsRaw; // Raw body from SYMBOLS
  String rawResponse;
}
