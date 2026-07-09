import email
import logging
import re
import smtplib
from email.header import decode_header, make_header
from email.message import EmailMessage

from imapclient import IMAPClient

from .db import Db
from .models import (
    ActionType,
    ConditionOperator,
    ConditionSubject,
    ImapConfig,
    Rule,
    SpamAction,
    SpamAssassinConfig,
    State,
)
from .spamassassin import SpamAssassinClient

logger = logging.getLogger(__name__)


def _decode_header(value: str) -> str:
    try:
        return str(make_header(decode_header(value)))
    except Exception:
        return value


class ProcessingService:
    def __init__(self, db: Db, rules: list[Rule], spam_config: SpamAssassinConfig) -> None:
        self.db = db
        self.rules = rules
        self.spam_config = spam_config
        self._current_smtp: smtplib.SMTP | smtplib.SMTP_SSL | None = None

    def process_account(self, imap_config: ImapConfig) -> None:
        logger.info(f"Connecting to {imap_config.name} ({imap_config.host})...")

        self._current_smtp = None

        try:
            with IMAPClient(
                imap_config.host,
                port=int(imap_config.port),
                ssl=imap_config.ssl,
                timeout=imap_config.connection_timeout_ms / 1000.0,
            ) as client:
                client.login(imap_config.username, imap_config.password)

                folder_info = client.select_folder(imap_config.inbox_folder)
                uid_validity = folder_info[b"UIDVALIDITY"]
                uid_next = folder_info[b"UIDNEXT"]

                state_key = f"{imap_config.name}:{imap_config.inbox_folder}"
                state = self.db.get_state(
                    state_key,
                    State(
                        imap_config_name=imap_config.name,
                        folder=imap_config.inbox_folder,
                        uid_validity=uid_validity,
                        last_processed_uid=0,
                    ),
                )

                if state.uid_validity != uid_validity:
                    state.uid_validity = uid_validity
                    state.last_processed_uid = 0

                start_uid = state.last_processed_uid + 1
                end_uid = uid_next - 1

                if start_uid > end_uid:
                    logger.info("No new messages.")
                    return

                CHUNK_SIZE = 50
                spam_client = None
                if imap_config.use_spam_assassin and self.spam_config.enabled:
                    spam_client = SpamAssassinClient(self.spam_config)

                last_seen_uid = state.last_processed_uid
                any_deleted = False

                for chunk_start in range(start_uid, end_uid + 1, CHUNK_SIZE):
                    chunk_end = min(chunk_start + CHUNK_SIZE - 1, end_uid)

                    messages = client.fetch(f"{chunk_start}:{chunk_end}", ["RFC822", "FLAGS"])
                    if not messages:
                        last_seen_uid = chunk_end
                        state.last_processed_uid = last_seen_uid
                        self.db.put_state(state)
                        continue

                    logger.info(
                        f"Processing chunk UID {chunk_start}..{chunk_end} "
                        f"({len(messages)} messages)."
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
                                if check.ok and check.is_spam:
                                    self._handle_spam(client, uid, imap_config)
                                    any_deleted = True
                                    last_seen_uid = max(last_seen_uid, uid)
                                    continue

                            for rule in self.rules:
                                if rule.imap_config_name != imap_config.name:
                                    continue

                                if self._evaluate_rule(rule, parsed_email):
                                    self._apply_rule(client, uid, rule, parsed_email, imap_config)
                                    if rule.action_type in [
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

                    state.last_processed_uid = last_seen_uid
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

    def _handle_spam(self, client: IMAPClient, uid: int, config: ImapConfig) -> None:
        if config.spam_action == SpamAction.DELETE:
            client.add_flags(uid, [b"\\Deleted"])
        elif config.spam_action == SpamAction.MOVE:
            if not client.folder_exists(config.spam_folder):
                client.create_folder(config.spam_folder)
            client.copy(uid, config.spam_folder)
            client.add_flags(uid, [b"\\Deleted"])
        elif config.spam_action == SpamAction.MARK_AS_READ:
            client.add_flags(uid, [b"\\Seen"])

    def _evaluate_rule(self, rule: Rule, msg: email.message.Message) -> bool:
        left = self._get_value_to_check(rule.condition_subject, msg)
        right = rule.condition_value

        # REGEX must use re.IGNORECASE flag only — lowercasing the pattern corrupts
        # escape sequences like \S→\s, \D→\d, \W→\w.
        if rule.condition_operator == ConditionOperator.REGEX:
            flags = 0 if rule.case_sensitive else re.IGNORECASE
            try:
                return bool(re.search(right, left, flags))
            except re.error as exc:
                logger.warning(f"Rule {rule.id}: invalid regex '{right}': {exc}")
                return False

        if not rule.case_sensitive:
            left = left.lower()
            right = right.lower()

        if rule.condition_operator == ConditionOperator.EQUALS:
            return left == right
        elif rule.condition_operator == ConditionOperator.NOT_EQUALS:
            return left != right
        elif rule.condition_operator == ConditionOperator.CONTAINS:
            return right in left
        elif rule.condition_operator == ConditionOperator.NOT_CONTAINS:
            return right not in left
        elif rule.condition_operator == ConditionOperator.STARTS_WITH:
            return left.startswith(right)
        elif rule.condition_operator == ConditionOperator.ENDS_WITH:
            return left.endswith(right)

        return False

    def _get_value_to_check(self, subject: ConditionSubject, msg: email.message.Message) -> str:
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
    ) -> None:
        if rule.action_type == ActionType.MOVE:
            if rule.dest_value:
                if not client.folder_exists(rule.dest_value):
                    client.create_folder(rule.dest_value)
                client.copy(uid, rule.dest_value)
                client.add_flags(uid, [b"\\Deleted"])
            else:
                logger.warning(f"Rule {rule.id}: MOVE has no dest_value, skipping UID {uid}")
        elif rule.action_type == ActionType.COPY:
            if rule.dest_value:
                if not client.folder_exists(rule.dest_value):
                    client.create_folder(rule.dest_value)
                client.copy(uid, rule.dest_value)
            else:
                logger.warning(f"Rule {rule.id}: COPY has no dest_value, skipping UID {uid}")
        elif rule.action_type == ActionType.DELETE:
            client.add_flags(uid, [b"\\Deleted"])
        elif rule.action_type == ActionType.MARK_READ:
            client.add_flags(uid, [b"\\Seen"])
        elif rule.action_type == ActionType.MARK_UNREAD:
            client.remove_flags(uid, [b"\\Seen"])
        elif rule.action_type == ActionType.FLAG:
            client.add_flags(uid, [b"\\Flagged"])
        elif rule.action_type == ActionType.ADD_LABEL:
            if rule.dest_value:
                try:
                    client.add_flags(uid, [rule.dest_value.encode("ascii")])
                except (UnicodeEncodeError, ValueError):
                    logger.warning(
                        f"Rule {rule.id}: ADD_LABEL dest_value contains "
                        f"non-ASCII: '{rule.dest_value}'"
                    )
            else:
                logger.warning(f"Rule {rule.id}: ADD_LABEL has no dest_value, skipping UID {uid}")
        elif rule.action_type == ActionType.REMOVE_LABEL:
            if rule.dest_value:
                try:
                    client.remove_flags(uid, [rule.dest_value.encode("ascii")])
                except (UnicodeEncodeError, ValueError):
                    logger.warning(
                        f"Rule {rule.id}: REMOVE_LABEL dest_value contains "
                        f"non-ASCII: '{rule.dest_value}'"
                    )
            else:
                logger.warning(
                    f"Rule {rule.id}: REMOVE_LABEL has no dest_value, skipping UID {uid}"
                )
        elif rule.action_type == ActionType.ARCHIVE:
            target = rule.dest_value or "Archive"
            if not client.folder_exists(target):
                client.create_folder(target)
            client.copy(uid, target)
            client.add_flags(uid, [b"\\Deleted"])
        elif rule.action_type == ActionType.FORWARD:
            if rule.dest_value and config.smtp_host:
                self._forward_message(msg, rule.dest_value, config)
            elif not rule.dest_value:
                logger.warning(f"Rule {rule.id}: FORWARD has no dest_value, skipping UID {uid}")
        elif rule.action_type == ActionType.STOP:
            pass

    def _forward_message(
        self, original_msg: email.message.Message, to_addr: str, config: ImapConfig
    ) -> None:
        fwd = EmailMessage()
        fwd["Subject"] = f"Fwd: {original_msg.get('Subject', '')}"
        fwd["From"] = config.username
        fwd["To"] = to_addr

        fwd.set_content("Forwarded message attached.")
        fwd.add_attachment(original_msg.as_bytes(), maintype="message", subtype="rfc822")

        if not self._current_smtp:
            smtp_timeout = config.connection_timeout_ms / 1000.0
            if config.smtp_ssl:
                self._current_smtp = smtplib.SMTP_SSL(
                    config.smtp_host, int(config.smtp_port), timeout=smtp_timeout
                )
            else:
                self._current_smtp = smtplib.SMTP(
                    config.smtp_host, int(config.smtp_port), timeout=smtp_timeout
                )
                self._current_smtp.starttls()
            if config.smtp_auth:
                self._current_smtp.login(config.smtp_username, config.smtp_password)

        self._current_smtp.send_message(fwd)
