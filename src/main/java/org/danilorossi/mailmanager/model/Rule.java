package org.danilorossi.mailmanager.model;

import com.sun.mail.imap.IMAPFolder;
import jakarta.mail.Address;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Store;
import java.io.IOException;
import java.util.Locale;
import java.util.logging.Logger;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.val;
import org.danilorossi.mailmanager.helpers.LogConfigurator;
import org.danilorossi.mailmanager.helpers.MailTextExtractor;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Rule {

  private static final Logger logger = Logger.getLogger(Rule.class.getName());

  static {
    LogConfigurator.configLog(logger);
  }

  @EqualsAndHashCode.Include @NonNull
  private String imapConfigName; // Nome della configurazione IMAP a cui si applica la regola

  @EqualsAndHashCode.Include @NonNull private ActionType actionType; // Tipo di azione da eseguire

  @EqualsAndHashCode.Include @NonNull
  private ConditionOperator conditionOperator; // Operatore di confronto

  @EqualsAndHashCode.Include @NonNull
  private ConditionSubject conditionSubject; // Soggetto della condizione

  @NonNull private String conditionValue; // Valore della condizione
  private String destValue; // Valore di destinazione (es. nome cartella)

  // Esegue l'azione sul messaggio
  public void apply(
      @NonNull final Message message,
      @NonNull final Folder sourceFolder,
      @NonNull final Store store)
      throws MessagingException {

    switch (actionType) {
      case MOVE:
        val moveTarget = store.getFolder(destValue);
        if (!moveTarget.exists()) {
          logger.severe(
              String.format(
                  "Errore: Cartella di destinazione '%s' non esiste. Regola ignorata.", destValue));
          return;
        }

        if (sourceFolder instanceof IMAPFolder imapSrc) {
          // server-side MOVE (se supportato)
          imapSrc.moveMessages(new Message[] {message}, moveTarget);
        } else {
          sourceFolder.copyMessages(new Message[] {message}, moveTarget); // fallback
          message.setFlag(Flags.Flag.DELETED, true);
        }
        logger.info(String.format("Messaggio spostato in %s: %s", destValue, message.getSubject()));
        break;

      case COPY:
        val copyTarget = store.getFolder(destValue);
        if (!copyTarget.exists()) {
          logger.severe(
              String.format(
                  "Errore: Cartella di destinazione '%s' non esiste. Regola ignorata.", destValue));
          return;
        }
        sourceFolder.copyMessages(new Message[] {message}, copyTarget);
        logger.info(String.format("Messaggio copiato in %s: %s", destValue, message.getSubject()));
        break;

      case DELETE:
        message.setFlag(Flags.Flag.DELETED, true);
        logger.info(String.format("Messaggio eliminato: %s", message.getSubject()));
        break;
    }
  }

  // Valuta se il messaggio soddisfa la condizione
  public boolean evaluate(@NonNull final Message message) throws MessagingException {

    val valueToCheck = getValueToCheck(message);
    if (valueToCheck == null) return false;

    switch (conditionOperator) {
      case EQUALS:
        return valueToCheck
            .toLowerCase(Locale.ROOT)
            .contains(String.valueOf(conditionValue).toLowerCase(Locale.ROOT));
      case NOT_EQUALS:
        return !valueToCheck
            .toLowerCase(Locale.ROOT)
            .contains(String.valueOf(conditionValue).toLowerCase(Locale.ROOT));
      case CONTAINS:
        return valueToCheck
            .toLowerCase(Locale.ROOT)
            .contains(String.valueOf(conditionValue).toLowerCase(Locale.ROOT));
      case NOT_CONTAINS:
        return !valueToCheck
            .toLowerCase(Locale.ROOT)
            .contains(String.valueOf(conditionValue).toLowerCase(Locale.ROOT));
      default:
        return false;
    }
  }

  // recupera gli indirizzi e li concatena in una stringa
  private String catAddresses(final Address[] addresses) {
    if (addresses == null) return "";

    val builder = new StringBuilder();
    for (val address : addresses) {
      if (address == null) continue;
      builder.append(address.toString());
      builder.append(" ");
    }
    return builder.toString();
  }

  // Estrae il valore da confrontare in base al soggetto
  private String getValueToCheck(@NonNull final Message message) throws MessagingException {
    try {
      switch (conditionSubject) {
        case SUBJECT:
          return message.getSubject();

        case FROM:
          return catAddresses(message.getFrom());

        case TO:
          return catAddresses(message.getRecipients(Message.RecipientType.TO));

        case CC:
          return catAddresses(message.getRecipients(Message.RecipientType.CC));

        case CCN:
          return catAddresses(message.getRecipients(Message.RecipientType.BCC));

        case MESSAGE:
          val content = message.getContent();
          if (content instanceof String) {
            return (String) content;
          }

          if (content instanceof Multipart) {
            return MailTextExtractor.extractTextFromMessage(message);
          }

          return null;

        default:
          return null;
      }
    } catch (IOException e) {
      logger.severe(
          String.format("Errore nella lettura del contenuto del messaggio: %s", e.getMessage()));
      return null;
    }
  }

  // Rappresentazione stringa per ActionListBox
  @Override
  public String toString() {
    return conditionSubject
        + " "
        + conditionOperator
        + " '"
        + conditionValue
        + "' -> "
        + actionType
        + (destValue != null ? " '" + destValue + "'" : "");
  }
}
