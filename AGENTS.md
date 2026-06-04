# AGENTS.md - mailmanager

Project: IMAP rule-based email sorter + SpamAssassin integration. Headless or TUI mode. JSON persistence, UID state tracking, single-instance lock.

## Structure (venv/ excluded)
- main.py: CLI entry (argparse: -tui/-imap/-rules),             portalocker, Db, ProcessingService or MailManagerApp
- mailmanager/
  - __init__.py
  - models.py: Pydantic models (Rule, ImapConfig, SpamAssassinConfig, State, Enums)
  - db.py: Db (SQLite, PKs, load/save, auto-migrate from JSON)
  - processing.py: ProcessingService (IMAP fetch, spam check, rule eval/apply, SMTP forward)
  - spamassassin.py: SpamAssassinClient (SPAMC socket)
  - tui.py: MailManagerApp (Textual placeholder)
- data/
  - imap-servers.json, rules.json (50+ rules), spam-assassin.json, processing-state.json, mailmanager.lock
- mypy.ini, requirements*.txt, analyze.{cmd,sh}

## Dependencies
requirements.txt: pydantic>=2, imapclient>=3, textual>=0.50, portalocker>=2.8
requirements-dev.txt: mypy, ruff

## Commands
- Run: python main.py [-tui|-imap|-rules]
- Analyze: analyze.sh / analyze.cmd (ruff + mypy, activate venv first)
- Lint: ruff check/format, mypy (mypy.ini: exclude=venv)
- Test: pytest tests (activate venv first)

## Conventions
- Python: type hints, Pydantic v2 (model_dump), camelCase JSON keys
- No comments unless requested. Terse responses.
- Exclude venv/, .mypy_cache/, .ruff_cache/ always
- Lazy load @rules/python.md only if referenced
- Security: no secrets in code/commits

## Rules
- Single instance via lockfile
- Atomic JSON writes
- IMAP UID state tracking
- Rule eval: FROM/SUBJECT/MESSAGE CONTAINS -> MOVE/FORWARD
- Spam: optional SpamAssassin CHECK

All agents must follow python rules and this file.