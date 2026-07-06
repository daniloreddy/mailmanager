from pathlib import Path

import pytest
from unittest.mock import patch
from mailmanager.processing import ProcessingService
from mailmanager.db import Db
from mailmanager.models import (
    ImapConfig,
    Rule,
    SpamAssassinConfig,
    State,
    ActionType,
    ConditionOperator,
    ConditionSubject,
)


@pytest.fixture
def mock_db(tmp_path: Path) -> Db:
    return Db(str(tmp_path / "data"))


def test_process_account_integration(mock_db: Db) -> None:
    # Setup
    imap_cfg = ImapConfig(name="test", host="localhost", username="u", password="p")
    rule = Rule(
        imapConfigName="test",
        actionType=ActionType.MOVE,
        conditionOperator=ConditionOperator.CONTAINS,
        conditionSubject=ConditionSubject.SUBJECT,
        conditionValue="MoveMe",
        destValue="TargetFolder",
    )
    mock_db.save_imaps([imap_cfg])
    mock_db.save_rule(rule)

    service = ProcessingService(mock_db, [rule], SpamAssassinConfig(enabled=False))

    # Mock IMAPClient
    with patch("mailmanager.processing.IMAPClient") as MockIMAP:
        client = MockIMAP.return_value.__enter__.return_value
        client.select_folder.return_value = {b"UIDVALIDITY": 123, b"UIDNEXT": 10}

        # Simulate one message with "MoveMe" in subject
        msg_data = {1: {b"RFC822": b"Subject: MoveMe\n\nBody", b"FLAGS": []}}
        client.fetch.return_value = msg_data
        client.folder_exists.return_value = True

        service.process_account(imap_cfg)

        # Verify
        client.copy.assert_called_once_with(1, "TargetFolder")
        client.add_flags.assert_called_with(1, [b"\\Deleted"])
        client.expunge.assert_called_once()

        # Check state update
        default_state = State(
            imapConfigName="test", folder="INBOX", uidValidity=0, lastProcessedUid=0
        )
        state = mock_db.get_state("test:INBOX", default_state)
        assert state.lastProcessedUid == 1
