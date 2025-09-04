package org.danilorossi.mailmanager.spamassassin;

import lombok.Value;

@Value
public class StatusLine {
  String protocol; // e.g. SPAMD/1.5
  int code; // e.g. 0 (EX_OK)
  String statusText; // e.g. EX_OK
}
