package org.danilorossi.mailmanager.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class State {
  @Builder.Default private String imapConfigName = ""; // Nome della configurazione IMAP
  @Builder.Default private long uidValidity = -1; // UIDValidity della cartella
  @Builder.Default private long lastProcessedUid = -1; // Ultimo UID processato
}
