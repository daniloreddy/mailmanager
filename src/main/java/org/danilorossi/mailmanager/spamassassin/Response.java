package org.danilorossi.mailmanager.spamassassin;

import java.util.Map;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class Response {
  String protocol;
  int code;
  String statusText;
  Map<String, String> headers;
  String body; // May be null (e.g., CHECK)
  String raw; // Entire textual response reconstructed (for logs/diagnostics)
}
