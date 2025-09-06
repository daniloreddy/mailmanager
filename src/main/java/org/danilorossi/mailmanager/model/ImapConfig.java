package org.danilorossi.mailmanager.model;

import java.util.Properties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.val;
import org.danilorossi.mailmanager.helpers.LangUtils;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Builder
@ToString(onlyExplicitlyIncluded = true)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ImapConfig {

  /** Azioni per gestione SPAM lato server */
  public enum SpamAction {
    DELETE, // flag DELETED (+expunge a fine ciclo)
    MOVE, // sposta in spamFolder
    MARK_AS_READ // segna come SEEN, lascia in Inbox
  }

  @Builder.Default private boolean useSpamAssassin = false;

  /** Azione da eseguire se SpamAssassin classifica come SPAM */
  @Builder.Default private SpamAction spamAction = SpamAction.DELETE;

  /** Cartella destinazione quando spamAction == MOVE */
  @Builder.Default private String spamFolder = "Junk";

  @Builder.Default
  @ToString.Include(rank = 100)
  private String name = ""; // display name

  @Builder.Default
  @ToString.Include(rank = 99)
  @EqualsAndHashCode.Include
  private String host = "";

  @Builder.Default
  @ToString.Include(rank = 98)
  @EqualsAndHashCode.Include
  private String port = "993";

  @Builder.Default @EqualsAndHashCode.Include private String username = "";

  @Builder.Default private String password = "";

  @Builder.Default @EqualsAndHashCode.Include private String inboxFolder = "INBOX";

  @Builder.Default private boolean ssl = true; // IMAPS (implicit TLS)
  @Builder.Default private boolean auth = true;

  // Timeouts (ms) con default prudenti
  @Builder.Default private int connectionTimeoutMs = 15_000;
  @Builder.Default private int readTimeoutMs = 30_000;
  @Builder.Default private int writeTimeoutMs = 15_000;

  /** Ritorna "imaps" se ssl=true, altrimenti "imap". */
  public String getStoreProtocol() {
    return ssl ? "imaps" : "imap";
  }

  /** Proprietà minime e robuste per Jakarta Mail. */
  public Properties toProperties() {
    validate(); // fail-fast

    val p = new Properties();
    val prefix = "mail.imap";

    p.put(LangUtils.s("{}.host", prefix), host);
    p.put(LangUtils.s("{}.port", prefix), port);
    p.put(LangUtils.s("{}.auth", prefix), String.valueOf(auth));
    p.put(LangUtils.s("{}.ssl.enable", prefix), String.valueOf(ssl));
    p.put(LangUtils.s("{}.starttls.enable", prefix), String.valueOf(!ssl));
    p.put(LangUtils.s("{}.connectiontimeout", prefix), String.valueOf(connectionTimeoutMs));
    p.put(LangUtils.s("{}.timeout", prefix), String.valueOf(readTimeoutMs));
    p.put(LangUtils.s("{}.writetimeout", prefix), String.valueOf(writeTimeoutMs));

    // facoltativi utili:
    p.put(LangUtils.s("{}.ssl.trust", prefix), host); // se serve "trust all": "*"
    p.put(LangUtils.s("{}.partialfetch", prefix), "true");

    return p;
  }

  /** Validazione essenziale per errori precoce e messaggi chiari. */
  public void validate() throws IllegalArgumentException {
    if (LangUtils.empty(host)) throw new IllegalArgumentException("IMAP host is blank");
    if (LangUtils.empty(username)) throw new IllegalArgumentException("IMAP username is blank");
    try {
      val portNum = Integer.parseInt(port);
      if (portNum <= 0 || portNum > 65535) throw new NumberFormatException();
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(LangUtils.s("IMAP port is invalid: ", port));
    }
    if (LangUtils.empty(inboxFolder))
      throw new IllegalArgumentException("IMAP inboxFolder is blank");
    // Vincolo: se si sceglie MOVE, spamFolder non può essere vuoto
    if (useSpamAssassin && spamAction == SpamAction.MOVE && LangUtils.empty(spamFolder)) {
      throw new IllegalArgumentException("Spam folder is blank while action is MOVE");
    }
  }
}
