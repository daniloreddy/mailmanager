from pathlib import Path

import pytest

from app.db import Db
from app.models import (
    ActionType,
    ConditionOperator,
    ConditionSubject,
    ImapConfig,
    Rule,
)


@pytest.fixture
def db(tmp_path: Path) -> Db:
    data_dir = tmp_path / "data"
    return Db(str(data_dir))


def test_db_init(db: Db) -> None:
    assert db.db_path.exists()


def test_imap_config_crud(db: Db) -> None:
    cfg = ImapConfig(name="test_account", host="imap.test.com", username="user", password="pass")
    db.save_imaps([cfg])

    loaded = db.load_imaps()
    assert len(loaded) == 1
    assert loaded[0].name == "test_account"
    assert loaded[0].host == "imap.test.com"


def test_rule_crud(db: Db) -> None:
    rule = Rule(
        imap_config_name="test_account",
        action_type=ActionType.MOVE,
        condition_operator=ConditionOperator.CONTAINS,
        condition_subject=ConditionSubject.SUBJECT,
        condition_value="test",
    )
    db.save_rule(rule)

    rules = db.load_rules()
    assert len(rules) == 1
    assert rules[0].imap_config_name == "test_account"
    assert rules[0].id is not None

    # Update
    rule_id = rules[0].id
    rules[0].condition_value = "updated"
    db.save_rule(rules[0])

    updated_rules = db.load_rules()
    assert updated_rules[0].condition_value == "updated"
    assert updated_rules[0].id == rule_id


def test_delete_rule(db: Db) -> None:
    rule = Rule(
        imap_config_name="test",
        action_type=ActionType.DELETE,
        condition_operator=ConditionOperator.EQUALS,
        condition_subject=ConditionSubject.FROM,
        condition_value="spam@spam.com",
    )
    db.save_rule(rule)
    rules = db.load_rules()
    rule_id = rules[0].id
    assert rule_id is not None

    db.delete_rule(rule_id)
    assert len(db.load_rules()) == 0
