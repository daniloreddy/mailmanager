import email
import logging
import re
import smtplib
from email.header import decode_header, make_header
from email.message import EmailMessage
from typing import List, Optional, Union
from imapclient import IMAPClient

from .models import (
    ImapConfig,
    Rule,
    SpamAssassinConfig,
    State,
    ActionType,
    ConditionOperator,
    ConditionSubject,
    SpamAction,
)
from .db import Db
from .spamassassin import SpamAssassinClient

logger = logging.getLogger(__name__)


def _decode_header(value: str) -> str:
    try:
        return str(make_header(decode_header(value)))
    except Exception:
        return value


class ProcessingService:
    def __init__(self, db: Db, rules: List[Rule], spam_config: SpamAssassinConfig):
        self.db = db
        self.rules = rules
        self.spam_config = spam_config
        self._current_smtp: Optional[Union[smtplib.SMTP, smtplib.SMTP_SSL]] = None

    def process_account(self, imap_config: ImapConfig):
        logger.info(f"Connecting to {imap_config.name} ({imap_config.host})...")

        self._current_smtp = None

        try:
            with IMAPClient(
                imap_config.host,
                port=int(imap_config.port),
                ssl=imap_config.ssl,
                timeout=imap_config.connectionTimeoutMs / 1000.0,
            ) as client:
                client.login(imap_config.username, imap_config.password)

                folder_info = client.select_folder(imap_config.inboxFolder)
                uid_validity = folder_info[b"UIDVALIDITY"]
                uid_next = folder_info[b"UIDNEXT"]

                state_key = f"{imap_config.name}:{imap_config.inboxFolder}"
                state = self.db.get_state(
                    state_key,
                    State(
                        imapConfigName=imap_config.name,
                        folder=imap_config.inboxFolder,
                        uidValidity=uid_validity,
                        lastProcessedUid=0,
                    ),
                )

                if state.uidValidity != uid_validity:
                    state.uidValidity = uid_validity
                    state.lastProcessedUid = 0

                start_uid = state.lastProcessedUid + 1
                end_uid = uid_next - 1

                if start_uid > end_uid:
                    logger.info("No new messages.")
                    return

                CHUNK_SIZE = 50
                spam_client = None
                if imap_config.useSpamAssassin and self.spam_config.enabled:
                    spam_client = SpamAssassinClient(self.spam_config)

                last_seen_uid = state.lastProcessedUid
                any_deleted = False

                for chunk_start in range(start_uid, end_uid + 1, CHUNK_SIZE):
                    chunk_end = min(chunk_start + CHUNK_SIZE - 1, end_uid)

                    messages = client.fetch(
                        f"{chunk_start}:{chunk_end}", ["RFC822", "FLAGS"]
                    )
                    if not messages:
                        last_seen_uid = chunk_end
                        state.lastProcessedUid = last_seen_uid
                        self.db.put_state(state)
                        continue

                    logger.info(
                        f"Processing chunk UID {chunk_start}..{chunk_end} ({len(messages)} messages)."
                    )

                    chunk_error = False

                    for uid, msg_data in messages.items():
                        raw_email = msg_data.get(b"RFC822")
                        if not raw_email:
                            last_seen_uid = max(last_seen_uid, uid)
                            continue

                        parsed_email = email.message_from_bytes(raw_email)

                        try:
                            if spam_client:
                                check = spam_client.check(raw_email)
                                if check.ok and check.isSpam:
                                    self._handle_spam(client, uid, imap_config)
                                    any_deleted = True
                                    last_seen_uid = max(last_seen_uid, uid)
                                    continue

                            for rule in self.rules:
                                if rule.imapConfigName != imap_config.name:
                                    continue

                                if self._evaluate_rule(rule, parsed_email):
                                    self._apply_rule(
                                        client, uid, rule, parsed_email, imap_config
                                    )
                                    if rule.actionType in [
                                        ActionType.DELETE,
                                        ActionType.MOVE,
                                        ActionType.ARCHIVE,
                                    ]:
                                        any_deleted = True
                                    break

                            last_seen_uid = max(last_seen_uid, uid)
                        except Exception as e:
                            logger.error(f"Error processing UID {uid}: {e}")
                            chunk_error = True
                            break

                    state.lastProcessedUid = last_seen_uid
                    self.db.put_state(state)

                    if chunk_error:
                        logger.error("Stopping account processing due to error.")
                        break

                if any_deleted:
                    client.expunge()

        except Exception as e:
            logger.error(f"Error connecting to {imap_config.name}: {e}")
        finally:
            if self._current_smtp:
                try:
                    self._current_smtp.quit()
                except Exception:
                    pass
                self._current_smtp = None

    def _handle_spam(self, client: IMAPClient, uid: int, config: ImapConfig):
        if config.spamAction == SpamAction.DELETE:
            client.add_flags(uid, [b"\\Deleted"])
        elif config.spamAction == SpamAction.MOVE:
            if not client.folder_exists(config.spamFolder):
                client.create_folder(config.spamFolder)
            client.copy(uid, config.spamFolder)
            client.add_flags(uid, [b"\\Deleted"])
        elif config.spamAction == SpamAction.MARK_AS_READ:
            client.add_flags(uid, [b"\\Seen"])

    def _evaluate_rule(self, rule: Rule, msg: email.message.Message) -> bool:
        left = self._get_value_to_check(rule.conditionSubject, msg)
        right = rule.conditionValue

        # REGEX must use re.IGNORECASE flag only — lowercasing the pattern corrupts
        # escape sequences like \S→\s, \D→\d, \W→\w.
        if rule.conditionOperator == ConditionOperator.REGEX:
            flags = 0 if rule.caseSensitive else re.IGNORECASE
            try:
                return bool(re.search(right, left, flags))
            except re.error as exc:
                logger.warning(f"Rule {rule.id}: invalid regex '{right}': {exc}")
                return False

        if not rule.caseSensitive:
            left = left.lower()
            right = right.lower()

        if rule.conditionOperator == ConditionOperator.EQUALS:
            return left == right
        elif rule.conditionOperator == ConditionOperator.NOT_EQUALS:
            return left != right
        elif rule.conditionOperator == ConditionOperator.CONTAINS:
            return right in left
        elif rule.conditionOperator == ConditionOperator.NOT_CONTAINS:
            return right not in left
        elif rule.conditionOperator == ConditionOperator.STARTS_WITH:
            return left.startswith(right)
        elif rule.conditionOperator == ConditionOperator.ENDS_WITH:
            return left.endswith(right)

        return False

    def _get_value_to_check(
        self, subject: ConditionSubject, msg: email.message.Message
    ) -> str:
        if subject == ConditionSubject.SUBJECT:
            return _decode_header(str(msg.get("Subject", "")))
        elif subject == ConditionSubject.FROM:
            return _decode_header(str(msg.get("From", "")))
        elif subject == ConditionSubject.TO:
            return _decode_header(str(msg.get("To", "")))
        elif subject == ConditionSubject.CC:
            return _decode_header(str(msg.get("Cc", "")))
        elif subject == ConditionSubject.BCC:
            return _decode_header(str(msg.get("Bcc", "")))
        elif subject == ConditionSubject.MESSAGE:
            body = ""
            if msg.is_multipart():
                for part in msg.walk():
                    if part.get_content_type() == "text/plain":
                        payload = part.get_payload(decode=True)
                        if isinstance(payload, bytes):
                            charset = part.get_content_charset() or "utf-8"
                            body += payload.decode(charset, errors="replace")
            else:
                payload = msg.get_payload(decode=True)
                if isinstance(payload, bytes):
                    charset = msg.get_content_charset() or "utf-8"
                    body = payload.decode(charset, errors="replace")
            return body
        return ""

    def _apply_rule(
        self,
        client: IMAPClient,
        uid: int,
        rule: Rule,
        msg: email.message.Message,
        config: ImapConfig,
    ):
        if rule.actionType == ActionType.MOVE:
            if rule.destValue:
                if not client.folder_exists(rule.destValue):
                    client.create_folder(rule.destValue)
                client.copy(uid, rule.destValue)
                client.add_flags(uid, [b"\\Deleted"])
            else:
                logger.warning(
                    f"Rule {rule.id}: MOVE has no destValue, skipping UID {uid}"
                )
        elif rule.actionType == ActionType.COPY:
            if rule.destValue:
                if not client.folder_exists(rule.destValue):
                    client.create_folder(rule.destValue)
                client.copy(uid, rule.destValue)
            else:
                logger.warning(
                    f"Rule {rule.id}: COPY has no destValue, skipping UID {uid}"
                )
        elif rule.actionType == ActionType.DELETE:
            client.add_flags(uid, [b"\\Deleted"])
        elif rule.actionType == ActionType.MARK_READ:
            client.add_flags(uid, [b"\\Seen"])
        elif rule.actionType == ActionType.MARK_UNREAD:
            client.remove_flags(uid, [b"\\Seen"])
        elif rule.actionType == ActionType.FLAG:
            client.add_flags(uid, [b"\\Flagged"])
        elif rule.actionType == ActionType.ADD_LABEL:
            if rule.destValue:
                try:
                    client.add_flags(uid, [rule.destValue.encode("ascii")])
                except (UnicodeEncodeError, ValueError):
                    logger.warning(
                        f"Rule {rule.id}: ADD_LABEL destValue contains non-ASCII: '{rule.destValue}'"
                    )
            else:
                logger.warning(
                    f"Rule {rule.id}: ADD_LABEL has no destValue, skipping UID {uid}"
                )
        elif rule.actionType == ActionType.REMOVE_LABEL:
            if rule.destValue:
                try:
                    client.remove_flags(uid, [rule.destValue.encode("ascii")])
                except (UnicodeEncodeError, ValueError):
                    logger.warning(
                        f"Rule {rule.id}: REMOVE_LABEL destValue contains non-ASCII: '{rule.destValue}'"
                    )
            else:
                logger.warning(
                    f"Rule {rule.id}: REMOVE_LABEL has no destValue, skipping UID {uid}"
                )
        elif rule.actionType == ActionType.ARCHIVE:
            target = rule.destValue or "Archive"
            if not client.folder_exists(target):
                client.create_folder(target)
            client.copy(uid, target)
            client.add_flags(uid, [b"\\Deleted"])
        elif rule.actionType == ActionType.FORWARD:
            if rule.destValue and config.smtpHost:
                self._forward_message(msg, rule.destValue, config)
            elif not rule.destValue:
                logger.warning(
                    f"Rule {rule.id}: FORWARD has no destValue, skipping UID {uid}"
                )
        elif rule.actionType == ActionType.STOP:
            pass

    def _forward_message(
        self, original_msg: email.message.Message, to_addr: str, config: ImapConfig
    ):
        fwd = EmailMessage()
        fwd["Subject"] = f"Fwd: {original_msg.get('Subject', '')}"
        fwd["From"] = config.username
        fwd["To"] = to_addr

        fwd.set_content("Forwarded message attached.")
        fwd.add_attachment(
            original_msg.as_bytes(), maintype="message", subtype="rfc822"
        )

        if not self._current_smtp:
            if config.smtpSsl:
                self._current_smtp = smtplib.SMTP_SSL(
                    config.smtpHost, int(config.smtpPort)
                )
            else:
                self._current_smtp = smtplib.SMTP(config.smtpHost, int(config.smtpPort))
                self._current_smtp.starttls()
            if config.smtpAuth:
                self._current_smtp.login(config.smtpUsername, config.smtpPassword)

        self._current_smtp.send_message(fwd)
