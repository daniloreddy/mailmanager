from enum import Enum
from typing import Optional
from pydantic import BaseModel


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
    id: Optional[int] = None
    imapConfigName: str
    actionType: ActionType
    conditionOperator: ConditionOperator
    conditionSubject: ConditionSubject
    conditionValue: str = ""
    destValue: str = ""
    caseSensitive: bool = False


class SpamAction(str, Enum):
    DELETE = "DELETE"
    MOVE = "MOVE"
    MARK_AS_READ = "MARK_AS_READ"


class ImapConfig(BaseModel):
    useSpamAssassin: bool = False
    spamAction: SpamAction = SpamAction.DELETE
    spamFolder: str = "Junk"
    name: str = ""
    host: str = ""
    port: str = "993"
    username: str = ""
    password: str = ""
    inboxFolder: str = "INBOX"
    ssl: bool = True
    auth: bool = True
    connectionTimeoutMs: int = 15000
    readTimeoutMs: int = 30000
    writeTimeoutMs: int = 15000
    smtpHost: str = ""
    smtpPort: str = "587"
    smtpUsername: str = ""
    smtpPassword: str = ""
    smtpSsl: bool = False
    smtpAuth: bool = True


class SpamAssassinConfig(BaseModel):
    enabled: bool = False
    host: str = "127.0.0.1"
    port: int = 783
    user: Optional[str] = None
    connectTimeoutMillis: int = 3000
    readTimeoutMillis: int = 5000


class State(BaseModel):
    imapConfigName: str
    folder: str
    uidValidity: int
    lastProcessedUid: int
    updatedAtEpochMs: int = 0

    def key(self) -> str:
        return f"{self.imapConfigName}:{self.folder}"
