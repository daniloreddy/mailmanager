import json
import logging
import sqlite3
from pathlib import Path

from .models import (
    ImapConfig,
    LoggingConfig,
    Rule,
    SchedulerConfig,
    SpamAssassinConfig,
    State,
    UiConfig,
)

logger = logging.getLogger(__name__)


class Db:
    def __init__(self, data_dir: str = "data") -> None:
        self.data_dir = Path(data_dir)
        self.data_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.db_path = self.data_dir / "mailmanager.db"
        self._init_db()
        self._migrate_from_json()

    def _init_db(self) -> None:
        with sqlite3.connect(self.db_path) as conn:
            c = conn.cursor()
            c.execute("PRAGMA journal_mode=WAL")
            c.execute("PRAGMA busy_timeout=5000")
            c.execute("""CREATE TABLE IF NOT EXISTS imap_configs (
                name TEXT PRIMARY KEY,
                data TEXT
            )""")
            c.execute("""CREATE TABLE IF NOT EXISTS rules (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                data TEXT
            )""")
            c.execute("""CREATE TABLE IF NOT EXISTS spam_config (
                id INTEGER PRIMARY KEY,
                data TEXT
            )""")
            c.execute("""CREATE TABLE IF NOT EXISTS states (
                key TEXT PRIMARY KEY,
                data TEXT
            )""")
            c.execute("""CREATE TABLE IF NOT EXISTS scheduler_config (
                id INTEGER PRIMARY KEY,
                data TEXT
            )""")
            c.execute("""CREATE TABLE IF NOT EXISTS logging_config (
                id INTEGER PRIMARY KEY,
                data TEXT
            )""")
            c.execute("""CREATE TABLE IF NOT EXISTS ui_config (
                id INTEGER PRIMARY KEY,
                data TEXT
            )""")

    def _migrate_from_json(self) -> None:
        old_files = {
            "imap-servers.json": lambda items: self.save_imaps([ImapConfig(**i) for i in items]),
            "rules.json": lambda items: [self.save_rule(Rule(**i)) for i in items],
            "spam-assassin.json": lambda data: self.save_spam_config(SpamAssassinConfig(**data)),
            "processing-state.json": lambda data: self.save_states(
                {k: State(**v) for k, v in data.items()}
            ),
        }

        for filename, save_func in old_files.items():
            filepath = self.data_dir / filename
            if filepath.exists():
                try:
                    with open(filepath, encoding="utf-8") as f:
                        data = json.load(f)
                    save_func(data)
                    filepath.unlink()
                except Exception as e:
                    logger.error(f"Migration error for {filename}: {e}")

    def load_spam_config(self) -> SpamAssassinConfig:
        with sqlite3.connect(self.db_path) as conn:
            c = conn.cursor()
            c.execute("SELECT data FROM spam_config WHERE id=1")
            row = c.fetchone()
        if row:
            return SpamAssassinConfig(**json.loads(row[0]))
        return SpamAssassinConfig()

    def save_spam_config(self, config: SpamAssassinConfig) -> None:
        with sqlite3.connect(self.db_path) as conn:
            c = conn.cursor()
            c.execute(
                "INSERT OR REPLACE INTO spam_config (id, data) VALUES (1, ?)",
                (json.dumps(config.model_dump(exclude_none=True, by_alias=True)),),
            )

    def load_imaps(self) -> list[ImapConfig]:
        with sqlite3.connect(self.db_path) as conn:
            c = conn.cursor()
            c.execute("SELECT data FROM imap_configs")
            rows = c.fetchall()
        return [ImapConfig(**json.loads(r[0])) for r in rows]

    def save_imaps(self, imaps: list[ImapConfig]) -> None:
        with sqlite3.connect(self.db_path) as conn:
            c = conn.cursor()
            for imap in imaps:
                c.execute(
                    "INSERT OR REPLACE INTO imap_configs (name, data) VALUES (?, ?)",
                    (imap.name, json.dumps(imap.model_dump(exclude_none=True, by_alias=True))),
                )

    def load_rules(self) -> list[Rule]:
        with sqlite3.connect(self.db_path) as conn:
            c = conn.cursor()
            c.execute("SELECT id, data FROM rules ORDER BY id ASC")
            rows = c.fetchall()
        rules = []
        for row_id, data_str in rows:
            rule = Rule(**json.loads(data_str))
            rule.id = row_id
            rules.append(rule)
        return rules

    def save_rule(self, rule: Rule) -> Rule:
        with sqlite3.connect(self.db_path) as conn:
            c = conn.cursor()
            data = json.dumps(rule.model_dump(exclude={"id"}, exclude_none=True, by_alias=True))
            if rule.id is not None:
                c.execute("UPDATE rules SET data = ? WHERE id = ?", (data, rule.id))
            else:
                c.execute("INSERT INTO rules (data) VALUES (?)", (data,))
                rule.id = c.lastrowid
        return rule

    def delete_rule(self, rule_id: int) -> None:
        with sqlite3.connect(self.db_path) as conn:
            c = conn.cursor()
            c.execute("DELETE FROM rules WHERE id = ?", (rule_id,))

    def delete_imap(self, name: str) -> None:
        with sqlite3.connect(self.db_path) as conn:
            c = conn.cursor()
            c.execute("DELETE FROM imap_configs WHERE name = ?", (name,))

    def load_states(self) -> dict[str, State]:
        with sqlite3.connect(self.db_path) as conn:
            c = conn.cursor()
            c.execute("SELECT key, data FROM states")
            rows = c.fetchall()
        return {k: State(**json.loads(d)) for k, d in rows}

    def save_states(self, states: dict[str, State]) -> None:
        with sqlite3.connect(self.db_path) as conn:
            c = conn.cursor()
            for k, v in states.items():
                c.execute(
                    "INSERT OR REPLACE INTO states (key, data) VALUES (?, ?)",
                    (k, json.dumps(v.model_dump(exclude_none=True, by_alias=True))),
                )

    def get_state(self, key: str, default: State) -> State:
        states = self.load_states()
        return states.get(key, default)

    def put_state(self, state: State) -> None:
        states = self.load_states()
        states[state.key()] = state
        self.save_states(states)

    def load_scheduler_config(self) -> SchedulerConfig:
        with sqlite3.connect(self.db_path) as conn:
            c = conn.cursor()
            c.execute("SELECT data FROM scheduler_config WHERE id=1")
            row = c.fetchone()
        if row:
            return SchedulerConfig(**json.loads(row[0]))
        return SchedulerConfig()

    def save_scheduler_config(self, config: SchedulerConfig) -> None:
        with sqlite3.connect(self.db_path) as conn:
            c = conn.cursor()
            c.execute(
                "INSERT OR REPLACE INTO scheduler_config (id, data) VALUES (1, ?)",
                (json.dumps(config.model_dump(by_alias=True)),),
            )

    def load_logging_config(self) -> LoggingConfig:
        with sqlite3.connect(self.db_path) as conn:
            c = conn.cursor()
            c.execute("SELECT data FROM logging_config WHERE id=1")
            row = c.fetchone()
        if row:
            return LoggingConfig(**json.loads(row[0]))
        return LoggingConfig()

    def save_logging_config(self, config: LoggingConfig) -> None:
        with sqlite3.connect(self.db_path) as conn:
            c = conn.cursor()
            c.execute(
                "INSERT OR REPLACE INTO logging_config (id, data) VALUES (1, ?)",
                (json.dumps(config.model_dump()),),
            )

    def load_ui_config(self) -> UiConfig:
        with sqlite3.connect(self.db_path) as conn:
            c = conn.cursor()
            c.execute("SELECT data FROM ui_config WHERE id=1")
            row = c.fetchone()
        if row:
            return UiConfig(**json.loads(row[0]))
        return UiConfig()

    def save_ui_config(self, config: UiConfig) -> None:
        with sqlite3.connect(self.db_path) as conn:
            c = conn.cursor()
            c.execute(
                "INSERT OR REPLACE INTO ui_config (id, data) VALUES (1, ?)",
                (json.dumps(config.model_dump(by_alias=True)),),
            )
