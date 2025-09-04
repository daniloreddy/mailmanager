package org.danilorossi.mailmanager.spamassassin;

import lombok.Value;

@Value
public class ScoreLine {
  boolean isSpam;
  double score;
  double threshold;
}
