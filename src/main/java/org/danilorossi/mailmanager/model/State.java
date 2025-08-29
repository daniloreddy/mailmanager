package org.danilorossi.mailmanager.model;

import lombok.Builder;
import lombok.Data;
import lombok.With;
import lombok.val;

/**
 * Stato del processing per una specifica cartella IMAP. - uidValidity: se cambia, gli UID
 * precedenti non sono più validi. - lastProcessedUid: ultimo UID già gestito (inclusivo).
 */
@Data
@Builder
@With
public class State {

  @Builder.Default private String imapConfigName = ""; // nome configurazione IMAP
  @Builder.Default private String folder = "INBOX"; // cartella (es. INBOX)
  @Builder.Default private long uidValidity = -1L; // UIDVALIDITY della cartella
  @Builder.Default private long lastProcessedUid = -1L; // ultimo UID processato
  @Builder.Default private long updatedAtEpochMs = 0L; // audit semplice

  /** Chiave comoda per mappe JSON tipo "imap:folder" -> State. */
  public String key() {
    return keyOf(imapConfigName, folder);
  }

  public static String keyOf(final String imapConfigName, final String folder) {
    val a = imapConfigName == null ? "" : imapConfigName;
    val b = folder == null ? "" : folder;
    return a + ":" + b;
  }

  /** True se l'UID corrente va processato (stesso UIDVALIDITY e uid > lastProcessedUid). */
  public boolean shouldProcess(final long uid, final long currentUidValidity) {
    if (uid <= 0) return false;
    if (uidValidity >= 0 && currentUidValidity >= 0 && uidValidity != currentUidValidity) {
      // stato non aggiornato: serve refreshValidity prima di valutare
      return true; // segnala che c'è lavoro da fare (reset esterno)
    }
    return uid > lastProcessedUid;
  }

  /** Aggiorna l'UIDVALIDITY; se cambia, azzera lastProcessedUid. */
  public State refreshValidity(final long currentUidValidity) {
    if (currentUidValidity < 0) return this;
    if (this.uidValidity != currentUidValidity) {
      return this.withUidValidity(currentUidValidity)
          .withLastProcessedUid(-1L)
          .withUpdatedAtEpochMs(System.currentTimeMillis());
    }
    return this;
  }

  /** Avanza il puntatore se l'UID è maggiore dell'attuale. */
  public State advanceTo(final long processedUid) {
    if (processedUid > this.lastProcessedUid) {
      return this.withLastProcessedUid(processedUid)
          .withUpdatedAtEpochMs(System.currentTimeMillis());
    }
    return this;
  }
}
