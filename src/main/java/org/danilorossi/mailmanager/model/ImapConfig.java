package org.danilorossi.mailmanager.model;

import java.util.Properties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.val;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Builder
@ToString(onlyExplicitlyIncluded = true)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ImapConfig {
  @Builder.Default
  @ToString.Include(rank = 100)
  private String name = ""; // Nome configurazione IMAP

  @Builder.Default
  @ToString.Include(rank = 99)
  @EqualsAndHashCode.Include
  private String host = ""; // Host server IMAP

  @Builder.Default
  @ToString.Include(rank = 98)
  @EqualsAndHashCode.Include
  private String port = "993"; // Porta server IMAP

  @Builder.Default private String username = ""; // Username per l'autenticazione
  @Builder.Default private String password = ""; // Password per l'autenticazione
  @Builder.Default private String inboxFolder = "INBOX"; // nome della cartella INBOX
  @Builder.Default private boolean ssl = true; // usa connessione SSL
  @Builder.Default private boolean auth = true; // usa autenticazione

  public Properties toProperties() {
    val properties = new Properties();
    properties.put("mail.imap.host", getHost());
    properties.put("mail.imap.port", getPort());
    properties.put("mail.imap.ssl.enable", String.valueOf(isSsl()));
    properties.put("mail.imap.auth", String.valueOf(isAuth()));
    return properties;
  }
}
