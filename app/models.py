from enum import Enum

from pydantic import BaseModel, ConfigDict
from pydantic.alias_generators import to_camel


class ActionType(str, Enum):
    MOVE = "MOVE"
    COPY = "COPY"
    DELETE = "DELETE"
    MARK_READ = "MARK_READ"
    MARK_UNREAD = "MARK_UNREAD"
    FLAG = "FLAG"
    ADD_LABEL = "ADD_LABEL"
    REMOVE_LABEL = "REMOVE_LABEL"
    ARCHIVE = "ARCHIVE"
    FORWARD = "FORWARD"
    STOP = "STOP"


class ConditionOperator(str, Enum):
    EQUALS = "EQUALS"
    NOT_EQUALS = "NOT_EQUALS"
    CONTAINS = "CONTAINS"
    NOT_CONTAINS = "NOT_CONTAINS"
    STARTS_WITH = "STARTS_WITH"
    ENDS_WITH = "ENDS_WITH"
    REGEX = "REGEX"


class ConditionSubject(str, Enum):
    SUBJECT = "SUBJECT"
    FROM = "FROM"
    TO = "TO"
    CC = "CC"
    BCC = "BCC"
    MESSAGE = "MESSAGE"


class Rule(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    id: int | None = None
    imap_config_name: str
    action_type: ActionType
    condition_operator: ConditionOperator
    condition_subject: ConditionSubject
    condition_value: str = ""
    dest_value: str = ""
    case_sensitive: bool = False


class SpamAction(str, Enum):
    DELETE = "DELETE"
    MOVE = "MOVE"
    MARK_AS_READ = "MARK_AS_READ"


class ImapConfig(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    use_spam_assassin: bool = False
    spam_action: SpamAction = SpamAction.DELETE
    spam_folder: str = "Junk"
    name: str = ""
    host: str = ""
    port: str = "993"
    username: str = ""
    password: str = ""
    inbox_folder: str = "INBOX"
    ssl: bool = True
    auth: bool = True
    connection_timeout_ms: int = 15000
    read_timeout_ms: int = 30000
    write_timeout_ms: int = 15000
    smtp_host: str = ""
    smtp_port: str = "587"
    smtp_username: str = ""
    smtp_password: str = ""
    smtp_ssl: bool = False
    smtp_auth: bool = True


class SpamAssassinConfig(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    enabled: bool = False
    host: str = "127.0.0.1"
    port: int = 783
    user: str | None = None
    connect_timeout_millis: int = 3000
    read_timeout_millis: int = 5000


class LoggingLevel(str, Enum):
    DEBUG = "DEBUG"
    INFO = "INFO"
    WARNING = "WARNING"
    ERROR = "ERROR"


class State(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    imap_config_name: str
    folder: str
    uid_validity: int
    last_processed_uid: int
    updated_at_epoch_ms: int = 0

    def key(self) -> str:
        return f"{self.imap_config_name}:{self.folder}"
