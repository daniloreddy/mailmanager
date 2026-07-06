import email
from typing import cast

import pytest
from mailmanager.db import Db
from mailmanager.processing import ProcessingService
from mailmanager.models import (
    Rule,
    ActionType,
    ConditionOperator,
    ConditionSubject,
    SpamAssassinConfig,
)


@pytest.fixture
def service() -> ProcessingService:
    # We don't need a real DB for logic tests that don't call DB methods
    return ProcessingService(cast(Db, None), [], SpamAssassinConfig())


def test_get_value_to_check_subject(service: ProcessingService) -> None:
    msg = email.message_from_string("Subject: Test Subject\n\nBody")
    val = service._get_value_to_check(ConditionSubject.SUBJECT, msg)
    assert val == "Test Subject"


def test_get_value_to_check_message(service: ProcessingService) -> None:
    msg = email.message_from_string("Subject: Test\n\nThis is the message body.")
    val = service._get_value_to_check(ConditionSubject.MESSAGE, msg)
    assert val.strip() == "This is the message body."


def test_evaluate_rule_contains(service: ProcessingService) -> None:
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


def test_evaluate_rule_regex(service: ProcessingService) -> None:
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


def test_evaluate_rule_case_sensitive(service: ProcessingService) -> None:
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


def test_get_value_to_check_rfc2047_subject(service: ProcessingService) -> None:
    # "Ciao Mondo" base64-encoded in UTF-8: Q2lhbyBNb25kbw==
    msg = email.message_from_string("Subject: =?utf-8?b?Q2lhbyBNb25kbw==?=\n\nBody")
    val = service._get_value_to_check(ConditionSubject.SUBJECT, msg)
    assert val == "Ciao Mondo"


def test_get_value_to_check_rfc2047_from(service: ProcessingService) -> None:
    # "Marco Rossi" QP-encoded in UTF-8
    msg = email.message_from_string(
        "From: =?utf-8?q?Marco_Rossi?= <marco@example.com>\n\nBody"
    )
    val = service._get_value_to_check(ConditionSubject.FROM, msg)
    assert "Marco Rossi" in val


def test_get_value_to_check_latin1_body(service: ProcessingService) -> None:
    raw = (
        b"Content-Type: text/plain; charset=iso-8859-1\n"
        b"Content-Transfer-Encoding: 8bit\n"
        b"\n"
        b"\xe0\xe8\xec\xf2\xf9"
    )
    msg = email.message_from_bytes(raw)
    val = service._get_value_to_check(ConditionSubject.MESSAGE, msg)
    assert val == "\xe0\xe8\xec\xf2\xf9"  # àèìòù decoded correctly


def test_evaluate_rule_regex_case_insensitive_no_pattern_corruption(
    service: ProcessingService,
) -> None:
    # \S+ must NOT become \s+ when caseSensitive=False
    rule = Rule(
        imapConfigName="test",
        actionType=ActionType.MOVE,
        conditionOperator=ConditionOperator.REGEX,
        conditionSubject=ConditionSubject.FROM,
        conditionValue=r"\S+@\S+",
        caseSensitive=False,
    )
    msg = email.message_from_string("From: user@example.com\n\nBody")
    assert service._evaluate_rule(rule, msg) is True

    msg_whitespace = email.message_from_string("From:    \n\nBody")
    assert service._evaluate_rule(rule, msg_whitespace) is False


def test_evaluate_rule_regex_invalid_pattern_returns_false(
    service: ProcessingService,
) -> None:
    rule = Rule(
        imapConfigName="test",
        actionType=ActionType.MOVE,
        conditionOperator=ConditionOperator.REGEX,
        conditionSubject=ConditionSubject.SUBJECT,
        conditionValue=r"[unclosed",
    )
    msg = email.message_from_string("Subject: test\n\nBody")
    assert service._evaluate_rule(rule, msg) is False  # no re.error propagates


def test_evaluate_rule_regex_case_insensitive_matches(
    service: ProcessingService,
) -> None:
    rule = Rule(
        imapConfigName="test",
        actionType=ActionType.MOVE,
        conditionOperator=ConditionOperator.REGEX,
        conditionSubject=ConditionSubject.SUBJECT,
        conditionValue=r"invoice",
        caseSensitive=False,
    )
    msg = email.message_from_string("Subject: INVOICE #1234\n\nBody")
    assert service._evaluate_rule(rule, msg) is True
