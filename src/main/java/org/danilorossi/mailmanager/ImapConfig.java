package org.danilorossi.mailmanager;

import java.util.Properties;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.val;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Builder
public class ImapConfig {
  @Builder.Default private String host = "";
  @Builder.Default private String port = "993";
  @Builder.Default private String username = "";
  @Builder.Default private String password = "";
  @Builder.Default private String inboxFolder = "INBOX";
  @Builder.Default private boolean ssl = true;
  @Builder.Default private boolean auth = true;

  public Properties toProperties() {
    val properties = new Properties();
    properties.put("mail.imap.host", getHost());
    properties.put("mail.imap.port", getPort());
    properties.put("mail.imap.ssl.enable", String.valueOf(isSsl()));
    properties.put("mail.imap.auth", String.valueOf(isAuth()));
    return properties;
  }
}
