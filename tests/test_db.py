import pytest
from mailmanager.db import Db
from mailmanager.models import (
    ImapConfig,
    Rule,
    ActionType,
    ConditionOperator,
    ConditionSubject,
)


@pytest.fixture
def db(tmp_path):
    data_dir = tmp_path / "data"
    return Db(str(data_dir))


def test_db_init(db):
    assert db.db_path.exists()


def test_imap_config_crud(db):
    cfg = ImapConfig(
        name="test_account", host="imap.test.com", username="user", password="pass"
    )
    db.save_imaps([cfg])

    loaded = db.load_imaps()
    assert len(loaded) == 1
    assert loaded[0].name == "test_account"
    assert loaded[0].host == "imap.test.com"


def test_rule_crud(db):
    rule = Rule(
        imapConfigName="test_account",
        actionType=ActionType.MOVE,
        conditionOperator=ConditionOperator.CONTAINS,
        conditionSubject=ConditionSubject.SUBJECT,
        conditionValue="test",
    )
    db.save_rule(rule)

    rules = db.load_rules()
    assert len(rules) == 1
    assert rules[0].imapConfigName == "test_account"
    assert rules[0].id is not None

    # Update
    rule_id = rules[0].id
    rules[0].conditionValue = "updated"
    db.save_rule(rules[0])

    updated_rules = db.load_rules()
    assert updated_rules[0].conditionValue == "updated"
    assert updated_rules[0].id == rule_id


def test_delete_rule(db):
    rule = Rule(
        imapConfigName="test",
        actionType=ActionType.DELETE,
        conditionOperator=ConditionOperator.EQUALS,
        conditionSubject=ConditionSubject.FROM,
        conditionValue="spam@spam.com",
    )
    db.save_rule(rule)
    rules = db.load_rules()
    rule_id = rules[0].id

    db.delete_rule(rule_id)
    assert len(db.load_rules()) == 0
