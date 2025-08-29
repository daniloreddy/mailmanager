package org.danilorossi.mailmanager.model;

import com.sun.mail.imap.IMAPFolder;
import jakarta.mail.*;
import java.io.IOException;
import java.util.logging.Logger;
import lombok.*;
import org.danilorossi.mailmanager.helpers.LangUtils;
import org.danilorossi.mailmanager.helpers.LogConfigurator;
import org.danilorossi.mailmanager.helpers.MailTextExtractor;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Rule {

  private static final Logger LOG = Logger.getLogger(Rule.class.getName());

  static {
    LogConfigurator.configLog(LOG);
  }

  @EqualsAndHashCode.Include @NonNull
  private String imapConfigName; // Nome configurazione IMAP a cui si applica

  @EqualsAndHashCode.Include @NonNull private ActionType actionType; // Azione da eseguire

  @EqualsAndHashCode.Include @NonNull private ConditionOperator conditionOperator; // Operatore

  @EqualsAndHashCode.Include @NonNull
  private ConditionSubject conditionSubject; // Campo del messaggio

  @NonNull @Builder.Default private String conditionValue = ""; // Valore di confronto

  @Builder.Default private String destValue = ""; // Es. cartella di destinazione

  @Builder.Default private boolean caseSensitive = false; // Confronti case-insensitive per default

  public void apply(
      @NonNull final Message message,
      @NonNull final Folder sourceFolder,
      @NonNull final Store store)
      throws MessagingException {

    switch (actionType) {
      case MOVE -> {
        if (LangUtils.emptyString(destValue)) {
          LangUtils.warn(LOG, "MOVE senza destValue: regola ignorata");
          return;
        }
        Folder target = store.getFolder(destValue);
        if (target == null || !target.exists()) {
          LangUtils.warn(LOG, "Cartella destinazione inesistente: {}", destValue);
          return;
        }
        // READ_WRITE per eliminare dalla sorgente
        ensureOpenRW(sourceFolder);
        ensureOpenRW(target);

        if (sourceFolder instanceof IMAPFolder imapSrc && target instanceof IMAPFolder imapDst) {
          imapSrc.moveMessages(new Message[] {message}, imapDst);
        } else {
          // fallback: COPY + DELETE
          sourceFolder.copyMessages(new Message[] {message}, target);
          message.setFlag(Flags.Flag.DELETED, true);
        }
        LangUtils.info(LOG, "Spostato in {}:{}", destValue, safeSubject(message));
      }

      case COPY -> {
        if (LangUtils.emptyString(destValue)) {
          LangUtils.warn(LOG, "COPY senza destValue: regola ignorata");
          return;
        }
        Folder target = store.getFolder(destValue);
        if (target == null || !target.exists()) {
          LangUtils.warn(LOG, "Cartella destinazione inesistente: {}", destValue);
          return;
        }
        ensureOpenRO(sourceFolder);
        ensureOpenRO(target); // per alcuni provider non serve, ma non fa male
        sourceFolder.copyMessages(new Message[] {message}, target);
        LangUtils.info(LOG, "Copiato in {}: {}", destValue, safeSubject(message));
      }

      case DELETE -> {
        ensureOpenRW(sourceFolder);
        message.setFlag(Flags.Flag.DELETED, true);
        LangUtils.info(LOG, "Eliminato: {}", safeSubject(message));
      }
    }
  }

  public boolean evaluate(@NonNull final Message message) throws MessagingException {
    final String left = LangUtils.normalize(getValueToCheck(message));
    final String right = LangUtils.normalize(conditionValue);

    return conditionOperator.test(left, right, caseSensitive);
  }

  private static String safeSubject(Message m) {
    try {
      return String.valueOf(m.getSubject());
    } catch (MessagingException e) {
      return "(no-subject)";
    }
  }

  private static void ensureOpenRO(Folder f) throws MessagingException {
    if (!f.isOpen()) f.open(Folder.READ_ONLY);
  }

  private static void ensureOpenRW(Folder f) throws MessagingException {
    if (!f.isOpen() || f.getMode() != Folder.READ_WRITE) f.open(Folder.READ_WRITE);
  }

  // Estrae il valore da confrontare in base al soggetto
  private String getValueToCheck(@NonNull final Message message) throws MessagingException {
    try {
      return switch (conditionSubject) {
        case SUBJECT -> message.getSubject();

        case FROM -> catAddresses(message.getFrom());
        case TO -> catAddresses(message.getRecipients(Message.RecipientType.TO));
        case CC -> catAddresses(message.getRecipients(Message.RecipientType.CC));
        case BCC ->
            catAddresses(message.getRecipients(Message.RecipientType.BCC)); // nota: spesso assente

        case MESSAGE -> {
          Object content = message.getContent();
          if (content instanceof String s) yield s;
          if (content instanceof Multipart) yield MailTextExtractor.extractTextFromMessage(message);
          yield "";
        }
      };
    } catch (IOException e) {
      LangUtils.err(LOG, "Errore lettura contenuto messaggio: {}", LangUtils.exMsg(e));
      return "";
    }
  }

  // Concatena indirizzi in stringa
  private String catAddresses(final Address[] addresses) {
    if (addresses == null || addresses.length == 0) return "";
    StringBuilder sb = new StringBuilder();
    for (Address a : addresses) {
      if (a != null) sb.append(a.toString()).append(' ');
    }
    return sb.toString().trim();
  }

  @Override
  public String toString() {
    return LangUtils.s(
        "{} {} '{}' -> {} '{}'",
        conditionSubject,
        conditionOperator,
        conditionValue,
        actionType,
        (LangUtils.emptyString(destValue) ? "-" : destValue));
  }
}
