package org.danilorossi.mailmanager.model;

import com.sun.mail.imap.IMAPFolder;
import jakarta.mail.*;
import java.io.IOException;
import lombok.*;
import lombok.extern.java.Log;
import org.danilorossi.mailmanager.helpers.LangUtils;
import org.danilorossi.mailmanager.helpers.LogConfigurator;
import org.danilorossi.mailmanager.helpers.MailUtils;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Log
public class Rule {

  static {
    LogConfigurator.configLog(log);
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
          LangUtils.warn(log, "MOVE senza destValue: regola ignorata");
          return;
        }

        MailUtils.ensureOpenRW(sourceFolder);
        final Folder target;
        try {
          target = MailUtils.ensureFolderExistsAndOpen(store, destValue);
        } catch (MessagingException me) {
          LangUtils.err(log, "Impossibile aprire/creare '{}': {}", destValue, LangUtils.exMsg(me));
          return;
        }

        if (sourceFolder instanceof IMAPFolder imapSrc && target instanceof IMAPFolder imapDst) {
          imapSrc.moveMessages(new Message[] {message}, imapDst);
        } else {
          // fallback: COPY + DELETE
          sourceFolder.copyMessages(new Message[] {message}, target);
          message.setFlag(Flags.Flag.DELETED, true);
        }
        LangUtils.info(log, "Spostato in {}:{}", destValue, MailUtils.safeSubject(message));
      }

      case COPY -> {
        if (LangUtils.emptyString(destValue)) {
          LangUtils.warn(log, "COPY senza destValue: regola ignorata");
          return;
        }
        MailUtils.ensureOpenRW(sourceFolder);
        final Folder target;
        try {
          target = MailUtils.ensureFolderExistsAndOpen(store, destValue);
        } catch (MessagingException me) {
          LangUtils.err(log, "Impossibile aprire/creare '{}': {}", destValue, LangUtils.exMsg(me));
          return;
        }
        sourceFolder.copyMessages(new Message[] {message}, target);
        LangUtils.info(log, "Copiato in {}: {}", destValue, MailUtils.safeSubject(message));
      }

      case DELETE -> {
        MailUtils.ensureOpenRW(sourceFolder);
        message.setFlag(Flags.Flag.DELETED, true);
        LangUtils.info(log, "Eliminato: {}", MailUtils.safeSubject(message));
      }

      case MARK_READ -> {
        MailUtils.ensureOpenRW(sourceFolder);
        message.setFlag(Flags.Flag.SEEN, true);
        LangUtils.info(log, "Marcato come letto: {}", MailUtils.safeSubject(message));
      }

      case MARK_UNREAD -> {
        MailUtils.ensureOpenRW(sourceFolder);
        message.setFlag(Flags.Flag.SEEN, false);
        LangUtils.info(log, "Marcato come non letto: {}", MailUtils.safeSubject(message));
      }

      case FLAG -> {
        MailUtils.ensureOpenRW(sourceFolder);
        message.setFlag(Flags.Flag.FLAGGED, true); // “stellina”
        LangUtils.info(log, "Aggiunta flag (FLAGGED): {}", MailUtils.safeSubject(message));
      }

      case ADD_LABEL -> {
        if (LangUtils.emptyString(destValue)) {
          LangUtils.warn(log, "ADD_LABEL senza destValue: regola ignorata");
          return;
        }
        if (!sourceFolder.getPermanentFlags().contains(Flags.Flag.USER)) {
          LangUtils.warn(
              log, "Server non supporta user flags (IMAP keywords), salto ADD/REMOVE_LABEL");
          return;
        }
        MailUtils.ensureOpenRW(sourceFolder);
        for (val label : MailUtils.splitLabels(destValue)) {
          message.setFlags(new Flags(label), true); // IMAP keywords (user flags)
        }
        LangUtils.info(log, "Aggiunte label {}: {}", destValue, MailUtils.safeSubject(message));
      }

      case REMOVE_LABEL -> {
        if (LangUtils.emptyString(destValue)) {
          LangUtils.warn(log, "REMOVE_LABEL senza destValue: regola ignorata");
          return;
        }
        if (!sourceFolder.getPermanentFlags().contains(Flags.Flag.USER)) {
          LangUtils.warn(
              log, "Server non supporta user flags (IMAP keywords), salto ADD/REMOVE_LABEL");
          return;
        }
        MailUtils.ensureOpenRW(sourceFolder);
        for (val label : MailUtils.splitLabels(destValue)) {
          message.setFlags(new Flags(label), false); // rimuove keyword
        }
        LangUtils.info(log, "Rimosse label {}: {}", destValue, MailUtils.safeSubject(message));
      }

      case ARCHIVE -> {
        // Se destValue presente, usa quello; altrimenti prova nomi comuni
        val targetName =
            LangUtils.emptyString(destValue) ? MailUtils.resolveArchiveName(store) : destValue;
        if (LangUtils.emptyString(targetName)) {
          LangUtils.warn(log, "ARCHIVE: nessuna cartella archivio disponibile");
          return;
        }
        final Folder target;
        try {
          target = MailUtils.ensureFolderExistsAndOpen(store, targetName);
        } catch (MessagingException me) {
          LangUtils.err(
              log, "ARCHIVE: impossibile aprire/creare '{}': {}", targetName, LangUtils.exMsg(me));
          return;
        }
        if (sourceFolder instanceof IMAPFolder imapSrc && target instanceof IMAPFolder imapDst) {
          imapSrc.moveMessages(new Message[] {message}, imapDst);
        } else {
          sourceFolder.copyMessages(new Message[] {message}, target);
          message.setFlag(Flags.Flag.DELETED, true);
        }
        LangUtils.info(log, "Archiviato in {}: {}", targetName, MailUtils.safeSubject(message));
      }

      case FORWARD -> {
        if (LangUtils.emptyString(destValue)) {
          LangUtils.warn(log, "FORWARD senza destValue (indirizzo): regola ignorata");
          return;
        }
        try {
          // Best-effort: usa le System properties per la Session SMTP (in attesa di coord. SMTP su
          // ImapConfig)
          val props = System.getProperties();
          if (props.getProperty("mail.smtp.host") == null
              && props.getProperty("mail.smtps.host") == null) {
            LangUtils.warn(
                log,
                "FORWARD: SMTP non configurato (mail.smtp[s].host assente), operazione saltata");
            return;
          }
          val session = Session.getInstance(props);

          val fwd = MailUtils.buildForward(session, message, destValue); // <— usa un tuo helper
          Transport.send(fwd);
          LangUtils.info(log, "Inoltrato a {}: {}", destValue, MailUtils.safeSubject(message));
        } catch (Exception ex) {
          LangUtils.err(log, "FORWARD fallito verso {}: {}", destValue, LangUtils.exMsg(ex));
        }
      }

      case STOP -> {
        // Con l’attuale motore (che fa break dopo la prima regola applicata) è di fatto un NO-OP.
        // Lasciamo un log per chiarezza.
        LangUtils.info(
            log, "STOP elaborazione ulteriori regole per: {}", MailUtils.safeSubject(message));
      }
    }
  }

  public boolean evaluate(@NonNull final Message message) throws MessagingException {
    final String left = LangUtils.normalize(getValueToCheck(message));
    final String right = LangUtils.normalize(conditionValue);

    return conditionOperator.test(left, right, caseSensitive);
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
          if (content instanceof Multipart) yield MailUtils.extractTextFromMessage(message);
          yield "";
        }
      };
    } catch (IOException e) {
      LangUtils.err(log, "Errore lettura contenuto messaggio: {}", LangUtils.exMsg(e));
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
