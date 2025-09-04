package org.danilorossi.mailmanager.spamassassin;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class CheckResult {
  boolean ok;
  boolean isSpam;
  Double score;
  Double threshold;
  String rawResponse;
}
