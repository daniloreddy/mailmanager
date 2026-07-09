# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

IMAP rule-based email sorter with SpamAssassin integration. Runs as a long-lived daemon: FastAPI server + NiceGUI web UI + background scheduler. UI served by NiceGUI mounted into FastAPI via `ui.run_with(app, mount_path="/ui", ...)` — the whole dashboard lives under `/ui`; `/login`, `/auth/*`, `/health` stay FastAPI routes at root.

## Commands

```bash
# Setup (Windows: venv | Linux/Mac: .venv)
python -m venv venv
./venv/Scripts/pip install -r requirements.txt -r requirements.dev.txt

# Run
./venv/Scripts/python main.py          # binds 127.0.0.1:8080, no auth; loads ./.env if present
REQUIRE_AUTH=true ./venv/Scripts/python main.py   # auth required; still binds 127.0.0.1 (HOST unset)
HOST=0.0.0.0 REQUIRE_AUTH=true ./venv/Scripts/python main.py   # auth required, reachable beyond localhost
PORT=9000 ./venv/Scripts/python main.py        # custom port
./venv/Scripts/python main.py --env-file .env.prod  # load a specific .env file

# Lint + type check + tests
scripts/checks.bat                     # Windows (ruff check/format + mypy + pytest)
scripts/checks.sh                      # Unix

# Tests
./venv/Scripts/pytest tests/                    # all tests
./venv/Scripts/pytest tests/test_db.py          # single file
./venv/Scripts/pytest tests/test_processing.py::test_name  # single test
```

## Architecture

```
main.py                  Entry: --env-file/.env (python-dotenv) → portalocker lock → configure_logging → uvicorn.run (workers=1)
app/
  auth.py                AuthManager: cookie-based session auth (scrypt + JWT), brute-force protection
mailmanager/
  models.py              Pydantic v2: Rule, ImapConfig, SpamAssassinConfig, SchedulerConfig,
                         LoggingConfig, UiConfig (autoRefreshEnabled, autoRefreshSeconds), State + Enums
  tz.py                  get_timezone(): resolves TZ env var via zoneinfo, falls back to UTC + logs warning
  db.py                  Db: SQLite CRUD, WAL mode, auto-migrates legacy JSON on first run
  processing.py          ProcessingService: IMAP fetch → spam check → rule eval → action (sync)
  spamassassin.py        SpamAssassinClient: raw SPAMC/1.5 socket protocol
  scheduler.py           SchedulerService: asyncio loop, runs ProcessingService in thread executor
  server.py              FastAPI app factory: lifespan (start/stop scheduler), cookie auth middleware,
                         NiceGUI mount via ui.run_with(). No separate REST API — NiceGUI pages read/write
                         Db and SchedulerService directly in-process via nicegui.app.state
                         GET /health — public, unauthenticated liveness check
  ui/
    theme.py             _page_setup() / _header() / _footer() / base_layout() context manager:
                         icon-nav header (bg-primary), dark/light toggle, no sidebar
    components.py        metric_card(), status_badge() reusable NiceGUI components
    pages/
      status.py          @ui.page("/")  — scheduler status + Run Now button, auto-refresh (UiConfig.autoRefreshEnabled / autoRefreshSeconds)
      imap.py            @ui.page("/imap") — IMAP config CRUD
      rules.py           @ui.page("/rules") — rule CRUD
      settings.py        @ui.page("/settings") — spam + scheduler + logging + Interfaccia (auto-refresh) config
scripts/
  checks.bat / checks.sh Lint + type check + tests (ruff, mypy, pytest); auto-creates venv
  run.bat / run.sh       Run server locally; auto-creates venv
  set_password.py        CLI to set login password (re-execs into existing venv, no auto-init; also runnable in Docker)
static/
  login.html             Self-contained login page (used when REQUIRE_AUTH is set)
data/
  mailmanager.db         SQLite (imap_configs, rules, spam_config, scheduler_config, logging_config, ui_config, states)
  auth.json              Password hash + JWT secret (auto-created; gitignored)
  storage_secret         NiceGUI storage_secret, stable across restarts (auto-created; gitignored)
  mailmanager.lock       portalocker single-instance guard
  mailmanager.log        RotatingFileHandler output, non-Docker runs only (5MB x 3 backups)
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
- `scheduler.trigger_run_now()` (called from the Status page's Run Now button) sets `_run_now_event`; returns `False` if already running
- `ProcessingService` constructed fresh each run — picks up latest rules/config automatically
- Interval changes take effect on the next cycle (no reload needed)

### Rule evaluation

`_get_value_to_check()` extracts FROM / SUBJECT / TO / CC / BCC / MESSAGE (text/plain only).
`_evaluate_rule()` supports: EQUALS, CONTAINS, STARTS_WITH, ENDS_WITH, REGEX, NOT_EQUALS, NOT_CONTAINS.

### Persistence (db.py)

- Tables: `imap_configs` (name PK), `rules` (id AUTOINCREMENT PK), `spam_config` (id=1), `scheduler_config` (id=1), `logging_config` (id=1), `ui_config` (id=1), `states` (key PK)
- WAL mode + busy_timeout=5000ms to handle concurrent UI writes during scheduler runs
- State key format: `{account_name}:{folder}` → JSON `{uidValidity, lastProcessedUid}`
- Auto-migration: if legacy JSON files exist in `data/`, they are imported and deleted atomically

## Deployment

- Registry: `ghcr.io/daniloreddy/mailmanager`
- CI/CD: `.github/workflows/docker-publish.yml` (triggers on push to main and tags)
- Volumes: `/app/data` (SQLite + lock)
- Env vars: `REQUIRE_AUTH`, `HOST`, `BIND_HOST`, `PORT`, `AUTH_SECURE_COOKIE`, `TRUSTED_PROXIES`, `TZ` (see Key invariants)
- `docker-compose.yml`/`docker-compose-dev.yml` set `TZ=${TZ:-UTC}` (container clock), `HOST=0.0.0.0` (fixed — container-internal bind, see Key invariants), `PORT=${PORT:-8080}`, and `NICEGUI_STORAGE_PATH=/app/data/.nicegui` (persists `app.storage.user`, e.g. dark mode, across container recreation); `docker-compose.yml` additionally publishes `ports:` at `${BIND_HOST:-127.0.0.1}:${PORT}:${PORT}` — deployer's choice for external reachability, unrelated to the container-internal `HOST`

## Key invariants

- Single instance enforced via `data/mailmanager.lock` (portalocker); lock held for full uvicorn lifetime
- `workers=1` in uvicorn.run is mandatory — multiple workers would spawn multiple scheduler loops
- SpamAssassin client is fail-open: socket errors → treat as non-spam
- SMTP connection is lazy and pooled per `process_account` call
- `text/html` email bodies are NOT matched by MESSAGE rules (only `text/plain` decoded)
- STOP action type halts the rule chain but takes no action on the message
- camelCase JSON keys in SQLite JSON columns (Pydantic field aliases)
- Auth and network binding are independent settings, deliberately decoupled: `REQUIRE_AUTH` (unset/false → no login; true → cookie session via AuthManager gates the whole app) does not influence `HOST`. `HOST` (default `127.0.0.1`, non-Docker runs only — read directly via `os.environ`, see `main.py`) is a pure deployer choice: e.g. `HOST=0.0.0.0` with no auth on a trusted LAN, or `HOST=127.0.0.1` (default) with `REQUIRE_AUTH=true` behind a Cloudflare/SSH tunnel exposed to the internet. In Docker, `HOST=0.0.0.0` is fixed unconditionally inside the container (container's own `127.0.0.1` is unreachable from the host — Docker-networking necessity, not a deployer choice); external reachability there is controlled separately by `BIND_HOST` on the Compose `ports:` mapping. If this invariant ever regresses (`HOST` re-coupled to `REQUIRE_AUTH`), it's a bug — see `uvicorn.md` §2 in global guidelines: "HOST is a deployer choice, not an application concern"
- NiceGUI is mounted at `/ui` (`ui.run_with(app, mount_path="/ui", ...)`), so `/ui/*` is the cookie-protected surface while `/login`, `/auth/*`, `/health` stay public FastAPI routes at root. `_BYPASS_PREFIXES` excludes `/ui/_nicegui` (verified at runtime against the installed NiceGUI 3.13.0: covers both `/ui/_nicegui_ws` websocket and `/ui/_nicegui/<version>/...` static/library/component assets) from the cookie middleware — re-verify this prefix if the pinned NiceGUI version changes, since it's not guaranteed stable across major versions
- No separate REST API: earlier versions exposed a Bearer-token `/api/*` surface for the pre-NiceGUI vanilla-JS frontend; removed after the NiceGUI migration left it with zero consumers (NiceGUI pages talk to `Db`/`SchedulerService` in-process)
- `/health` is always public (in `_PUBLIC_PATHS`), regardless of `REQUIRE_AUTH`
- Logging: always stdout (captured by `docker compose logs`); local (non-Docker) runs also get a `RotatingFileHandler` at `data/mailmanager.log` — detected via `Path("/.dockerenv").exists()`; level change via Settings page takes effect immediately
- `.env` is loaded via `python-dotenv` at the top of `main.py` (`--env-file` CLI flag, else nearest `.env`), before `mailmanager.server`/`app.auth` are imported — those modules read `REQUIRE_AUTH`/`TRUSTED_PROXIES` from `os.environ` at import time, so load order matters
- `TZ` (IANA name, e.g. `Europe/Rome`) drives UI timestamp display via `mailmanager/tz.py`; unset/invalid → UTC with a logged warning. It's a boot-time-only setting like `PORT`/`HOST`, not part of the hot-reloadable `UiConfig`

## UI Guidelines

- Pages showing aggregated data or summaries (dashboards, status views) **must** support auto-refresh via `ui.timer`.
- Auto-refresh is controlled by a **single global** `UiConfig` stored in `ui_config` (id=1):
  - `autoRefreshEnabled: bool` — whether auto-refresh is active
  - `autoRefreshSeconds: int` — interval in seconds (min 1)
- Both fields are exposed in Settings → "Interfaccia" card (badge `hot-reload`). Changing them takes effect on the next page load.
- Every dashboard page must show a refresh label (always visible, right-aligned, `text-caption text-grey-6`), updated inside the refresh callback:
  - enabled → `Aggiornato: HH:MM:SS · auto-refresh Xs`
  - disabled → `auto-refresh disabilitato` (and no `ui.timer` is created)
- Pattern for every dashboard page: see `pages/status.py` (refreshable content + refresh label + conditional `ui.timer`).
- `UiConfig` is app-wide: one setting governs all dashboard pages simultaneously.
