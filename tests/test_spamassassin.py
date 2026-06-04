import pytest
from mailmanager.spamassassin import SpamAssassinClient
from mailmanager.models import SpamAssassinConfig


@pytest.fixture
def client():
    return SpamAssassinClient(SpamAssassinConfig())


def test_parse_spam_header_true(client):
    header = "True ; score=10.5 / required=5.0"
    is_spam, score, threshold = client._parse_spam_header(header)
    assert is_spam is True
    assert score == 10.5
    assert threshold == 5.0


def test_parse_spam_header_false(client):
    header = "False ; score=2.1 / required=5.0"
    is_spam, score, threshold = client._parse_spam_header(header)
    assert is_spam is False
    assert score == 2.1
    assert threshold == 5.0


def test_parse_headers(client):
    response = "SPAMD/1.5 0 EX_OK\r\nSpam: True ; score=10.5 / required=5.0\r\nContent-length: 0\r\n\r\n"
    headers = client._parse_headers(response)
    assert headers["spam"] == "True ; score=10.5 / required=5.0"
    assert headers["content-length"] == "0"
