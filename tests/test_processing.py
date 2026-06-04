import email
import pytest
from mailmanager.processing import ProcessingService
from mailmanager.models import (
    Rule,
    ActionType,
    ConditionOperator,
    ConditionSubject,
    SpamAssassinConfig,
)


@pytest.fixture
def service():
    # We don't need a real DB for logic tests that don't call DB methods
    return ProcessingService(None, [], SpamAssassinConfig())


def test_get_value_to_check_subject(service):
    msg = email.message_from_string("Subject: Test Subject\n\nBody")
    val = service._get_value_to_check(ConditionSubject.SUBJECT, msg)
    assert val == "Test Subject"


def test_get_value_to_check_message(service):
    msg = email.message_from_string("Subject: Test\n\nThis is the message body.")
    val = service._get_value_to_check(ConditionSubject.MESSAGE, msg)
    assert val.strip() == "This is the message body."


def test_evaluate_rule_contains(service):
    rule = Rule(
        imapConfigName="test",
        actionType=ActionType.MOVE,
        conditionOperator=ConditionOperator.CONTAINS,
        conditionSubject=ConditionSubject.SUBJECT,
        conditionValue="URGENT",
    )
    msg = email.message_from_string("Subject: This is URGENT!\n\nBody")
    assert service._evaluate_rule(rule, msg) is True

    msg2 = email.message_from_string("Subject: Normal message\n\nBody")
    assert service._evaluate_rule(rule, msg2) is False


def test_evaluate_rule_regex(service):
    rule = Rule(
        imapConfigName="test",
        actionType=ActionType.MOVE,
        conditionOperator=ConditionOperator.REGEX,
        conditionSubject=ConditionSubject.FROM,
        conditionValue=r".*@gmail\.com",
    )
    msg = email.message_from_string("From: user@gmail.com\n\nBody")
    assert service._evaluate_rule(rule, msg) is True

    msg2 = email.message_from_string("From: user@outlook.com\n\nBody")
    assert service._evaluate_rule(rule, msg2) is False


def test_evaluate_rule_case_sensitive(service):
    rule = Rule(
        imapConfigName="test",
        actionType=ActionType.MOVE,
        conditionOperator=ConditionOperator.EQUALS,
        conditionSubject=ConditionSubject.SUBJECT,
        conditionValue="ExactMatch",
        caseSensitive=True,
    )
    msg = email.message_from_string("Subject: ExactMatch\n\nBody")
    assert service._evaluate_rule(rule, msg) is True

    msg2 = email.message_from_string("Subject: exactmatch\n\nBody")
    assert service._evaluate_rule(rule, msg2) is False
