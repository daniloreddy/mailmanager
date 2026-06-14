# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

IMAP rule-based email sorter with SpamAssassin integration. Runs as a long-lived daemon: HTTP server (FastAPI) + background scheduler that processes mail at a configurable interval. Web UI served at `/`.

## Commands

```bash
# Setup
python -m venv venv
./venv/Scripts/pip install -r requirements.txt -r requirements-dev.txt

# Run
./venv/Scripts/python main.py          # binds 127.0.0.1:8080 (no auth)
MAILMANAGER_API_KEY=secret ./venv/Scripts/python main.py   # binds 0.0.0.0:8080 (auth required)
MAILMANAGER_PORT=9000 ./venv/Scripts/python main.py        # custom port

# Lint + type check
scripts/analyze.cmd                    # Windows (ruff check/format + mypy)
scripts/analyze.sh                     # Unix

# Tests
./venv/Scripts/pytest tests/                    # all tests
./venv/Scripts/pytest tests/test_db.py          # single file
./venv/Scripts/pytest tests/test_processing.py::test_name  # single test
```

## Architecture

```
main.py                  Entry: portalocker lock → configure_logging → uvicorn.run (workers=1)
mailmanager/
  models.py              Pydantic v2: Rule, ImapConfig, SpamAssassinConfig, SchedulerConfig,
                         LoggingConfig, State + Enums
  db.py                  Db: SQLite CRUD, WAL mode, auto-migrates legacy JSON on first run
  processing.py          ProcessingService: IMAP fetch → spam check → rule eval → action (sync)
  spamassassin.py        SpamAssassinClient: raw SPAMC/1.5 socket protocol
  scheduler.py           SchedulerService: asyncio loop, runs ProcessingService in thread executor
  server.py              FastAPI app factory: lifespan (start/stop scheduler), auth, static files
  api/
    deps.py              get_db / get_scheduler FastAPI dependencies
    configs.py           GET/POST/PUT/DELETE /api/configs
    rules.py             GET/POST/PUT/DELETE /api/rules
    spam.py              GET/PUT /api/spam
    scheduler.py         GET /api/scheduler/status, POST /api/scheduler/run, GET/PUT /api/scheduler/config
    logging_config.py    GET/PUT /api/logging/config (applies level change immediately)
web/
  index.html             SPA: vanilla JS, 4 tabs (Status / IMAP / Rules / Settings)
data/
  mailmanager.db         SQLite (imap_configs, rules, spam_config, scheduler_config, logging_config, states)
  mailmanager.lock       portalocker single-instance guard
logs/
  mailmanager.log        RotatingFileHandler output
```

### Processing flow (ProcessingService.process_account)

1. Connect to IMAP; fetch UIDs after `lastProcessedUid` for `lastUidValidity`
2. Chunk UIDs (50/batch); optionally check each via SpamAssassin
3. For spam: apply SpamAction (DELETE / MOVE / MARK)
4. For non-spam: evaluate rules sequentially; first match wins; any matched rule breaks the chain (`STOP` action matches and does nothing else)
5. Rules are filtered by `imapConfigName` — only rules matching the current account are evaluated
6. Actions: MOVE, COPY, DELETE, MARK_READ, MARK_UNREAD, FLAG, ADD_LABEL, REMOVE_LABEL, ARCHIVE, FORWARD (lazy SMTP pool)
7. DELETE/MOVE/ARCHIVE flag messages `\Deleted`; a single `expunge()` runs at end of account if any deletes occurred
8. Persist `lastProcessedUid` after each chunk; on per-message exception, chunk aborts at that UID

### Scheduler (scheduler.py)

- `SchedulerService._loop()` runs as an asyncio task (created inside FastAPI `lifespan`)
- Sleep is interruptible: `asyncio.wait_for(_run_now_event.wait(), timeout=intervalSeconds)`
- `POST /api/scheduler/run` sets `_run_now_event`; returns 409 if already running
- `ProcessingService` constructed fresh each run — picks up latest rules/config automatically
- Interval changes take effect on the next cycle (no reload needed)

### Rule evaluation

`_get_value_to_check()` extracts FROM / SUBJECT / TO / CC / BCC / MESSAGE (text/plain only).
`_evaluate_rule()` supports: EQUALS, CONTAINS, STARTS_WITH, ENDS_WITH, REGEX, NOT_EQUALS, NOT_CONTAINS.

### Persistence (db.py)

- Tables: `imap_configs` (name PK), `rules` (id AUTOINCREMENT PK), `spam_config` (id=1), `scheduler_config` (id=1), `logging_config` (id=1), `states` (key PK)
- WAL mode + busy_timeout=5000ms to handle concurrent API writes during scheduler runs
- State key format: `{account_name}:{folder}` → JSON `{uidValidity, lastProcessedUid}`
- Auto-migration: if legacy JSON files exist in `data/`, they are imported and deleted atomically

## Deployment

- Registry: `ghcr.io/daniloreddy/mailmanager`
- CI/CD: `.github/workflows/docker-publish.yml` (triggers on push to main and tags)
- Volumes: `/app/data` (SQLite + lock), `/app/logs` (log files)
- Env vars: `MAILMANAGER_API_KEY` (enables network bind + auth), `MAILMANAGER_PORT` (default 8080), `SPAMASSASSIN_HOST`

## Key invariants

- Single instance enforced via `data/mailmanager.lock` (portalocker); lock held for full uvicorn lifetime
- `workers=1` in uvicorn.run is mandatory — multiple workers would spawn multiple scheduler loops
- SpamAssassin client is fail-open: socket errors → treat as non-spam
- SMTP connection is lazy and pooled per `process_account` call
- `text/html` email bodies are NOT matched by MESSAGE rules (only `text/plain` decoded)
- STOP action type halts the rule chain but takes no action on the message
- camelCase JSON keys in SQLite JSON columns (Pydantic field aliases)
- Auth: if `MAILMANAGER_API_KEY` unset → 127.0.0.1 only, no token check; if set → 0.0.0.0, Bearer token required on all `/api/*`
- Logging level change via API takes effect immediately; maxBytes/backupCount take effect on next restart
