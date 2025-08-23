package org.danilorossi.mailmanager;

import java.io.IOException;
import java.util.logging.Logger;

import javax.mail.Address;
import javax.mail.Flags;
import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.Store;

import org.danilorossi.mailmanager.helpers.LogConfigurator;
import org.danilorossi.mailmanager.helpers.MailTextExtractor;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.val;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Builder
public class Rule {

  private static final Logger logger = Logger.getLogger(Rule.class.getName());

  static {
    LogConfigurator.configLog(logger);
  }

  private ActionType actionType;
  private ConditionOperator conditionOperator;
  private ConditionSubject conditionSubject;
  private String conditionValue;
  private String destValue;

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
              "Errore: Cartella di destinazione '" + destValue + "' non esiste. Regola ignorata.");
          return;
        }
        sourceFolder.copyMessages(new Message[] {message}, moveTarget);
        message.setFlag(Flags.Flag.DELETED, true);
        logger.info("Messaggio spostato in " + destValue + ": " + message.getSubject());
        break;

      case COPY:
        val copyTarget = store.getFolder(destValue);
        if (!copyTarget.exists()) {
          logger.severe(
              "Errore: Cartella di destinazione '" + destValue + "' non esiste. Regola ignorata.");

          return;
        }
        sourceFolder.copyMessages(new Message[] {message}, copyTarget);
        logger.info("Messaggio copiato in " + destValue + ": " + message.getSubject());
        break;

      case DELETE:
        message.setFlag(Flags.Flag.DELETED, true);
        logger.info("Messaggio eliminato: " + message.getSubject());
        break;
    }
  }

  // Valuta se il messaggio soddisfa la condizione
  public boolean evaluate(@NonNull final Message message) throws MessagingException {

    val valueToCheck = getValueToCheck(message);
    if (valueToCheck == null) return false;

    switch (conditionOperator) {
      case EQUALS:
        return valueToCheck.equalsIgnoreCase(conditionValue);
      case NOT_EQUALS:
        return !valueToCheck.equalsIgnoreCase(conditionValue);
      case CONTAINS:
        return valueToCheck.toLowerCase().contains(conditionValue.toLowerCase());
      case NOT_CONTAINS:
        return !valueToCheck.toLowerCase().contains(conditionValue.toLowerCase());
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
      builder.append(address.
    		  toString());
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
      logger.severe("Errore nella lettura del contenuto del messaggio: " + e.getMessage());
      return null;
    }
  }

  // Rappresentazione stringa per ActionListBox
  @Override
  public String toString() {
    return conditionSubject
        + " "
        + conditionOperator
        + " \""
        + conditionValue
        + "\" -> "
        + actionType
        + (destValue != null ? " \"" + destValue + "\"" : "");
  }
}
