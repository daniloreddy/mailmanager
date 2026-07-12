import json
import logging
import sqlite3
from pathlib import Path

from redberry_webkit.config import ConfigManager

from .models import ImapConfig, Rule, State

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
            c.execute("""CREATE TABLE IF NOT EXISTS states (
                key TEXT PRIMARY KEY,
                data TEXT
            )""")

    def _migrate_from_json(self) -> None:
        old_files = {
            "imap-servers.json": lambda items: self.save_imaps([ImapConfig(**i) for i in items]),
            "rules.json": lambda items: [self.save_rule(Rule(**i)) for i in items],
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


def migrate_legacy_config_to_env(data_dir: Path, config: ConfigManager) -> None:
    """One-time migration: fold values from the pre-ConfigManager single-row Db tables
    (ui_config/scheduler_config/logging_config/spam_config) into .env, then leave a
    marker so this never re-runs. Those tables/columns may still physically exist in
    an upgraded instance's mailmanager.db even though Db no longer creates or reads
    them going forward — this is the only remaining reader, and only ever once.
    """
    marker = data_dir / ".config_migrated_from_db"
    if marker.exists():
        return

    db_path = data_dir / "mailmanager.db"
    try:
        updates: dict[str, str] = {}
        if db_path.exists():
            with sqlite3.connect(db_path) as conn:
                c = conn.cursor()
                for table in ("ui_config", "scheduler_config", "logging_config", "spam_config"):
                    try:
                        c.execute(f"SELECT data FROM {table} WHERE id=1")
                        row = c.fetchone()
                    except sqlite3.OperationalError:
                        row = None
                    if not row:
                        continue
                    data = json.loads(row[0])
                    if table == "ui_config":
                        if "autoRefreshEnabled" in data:
                            updates["REFRESH_ENABLED"] = str(data["autoRefreshEnabled"]).lower()
                        if "autoRefreshSeconds" in data:
                            updates["REFRESH_INTERVAL"] = str(data["autoRefreshSeconds"])
                    elif table == "scheduler_config":
                        if "enabled" in data:
                            updates["SCHEDULER_ENABLED"] = str(data["enabled"]).lower()
                        if "intervalSeconds" in data:
                            updates["SCHEDULER_INTERVAL_SECONDS"] = str(data["intervalSeconds"])
                    elif table == "logging_config":
                        if "level" in data:
                            updates["LOG_LEVEL"] = str(data["level"])
                    elif table == "spam_config":
                        if "enabled" in data:
                            updates["SPAM_ENABLED"] = str(data["enabled"]).lower()
                        if "host" in data:
                            updates["SPAM_HOST"] = str(data["host"])
                        if "port" in data:
                            updates["SPAM_PORT"] = str(data["port"])
                        if data.get("user"):
                            updates["SPAM_USER"] = str(data["user"])
                        if "connectTimeoutMillis" in data:
                            updates["SPAM_CONNECT_TIMEOUT_MS"] = str(data["connectTimeoutMillis"])
                        if "readTimeoutMillis" in data:
                            updates["SPAM_READ_TIMEOUT_MS"] = str(data["readTimeoutMillis"])
        if updates:
            config.update_many(updates)
            logger.info("Migrated %d config key(s) from Db to .env", len(updates))
        marker.parent.mkdir(parents=True, exist_ok=True)
        marker.touch()
    except (OSError, ValueError, sqlite3.Error) as exc:
        logger.warning("Config migration from Db failed, will retry next boot: %s", exc)
