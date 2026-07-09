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
./venv/Scripts/python -m app.main          # binds 127.0.0.1:8080, no auth; loads ./.env if present
REQUIRE_AUTH=true ./venv/Scripts/python -m app.main   # auth required; still binds 127.0.0.1 (HOST unset)
HOST=0.0.0.0 REQUIRE_AUTH=true ./venv/Scripts/python -m app.main   # auth required, reachable beyond localhost
PORT=9000 ./venv/Scripts/python -m app.main        # custom port
./venv/Scripts/python -m app.main --env-file .env.prod  # load a specific .env file
./venv/Scripts/python -m app.main --host 0.0.0.0 --port 9000 --dev  # CLI flags override env vars; --dev enables uvicorn reload

# Lint + type check + tests
scripts/checks.bat                     # Windows (ruff check/format + mypy + pytest)
scripts/checks.sh                      # Unix

# Tests
./venv/Scripts/pytest tests/                    # all tests
./venv/Scripts/pytest tests/test_db.py          # single file
./venv/Scripts/pytest tests/test_processing.py::test_name  # single test
```

## Architecture

All production code lives under `app/` — entrypoint, business logic, and UI (per `uvicorn.md` §3 in the global guidelines: no sibling top-level package for code the running app imports; only `tests/`, `static/`, `scripts/`, `data/` may live outside `app/`).

```
app/
  main.py                Entrypoint AND FastAPI app — module-level `app = FastAPI(...)`, no separate
                         factory. --env-file/.env (python-dotenv, before any other project import) →
                         logging.basicConfig → lifespan acquires the portalocker lock and constructs
                         Db/SchedulerService/logging config (so this runs both under Docker's
                         `uvicorn app.main:app` and local `python -m app.main`, not only
                         behind `__main__`) → routes/middleware (health, cookie auth gate, login/auth
                         routes) → NiceGUI mount via ui.run_with(). No separate REST API — NiceGUI pages
                         read/write Db and SchedulerService directly in-process via nicegui.app.state.
                         GET /health — public, unauthenticated liveness check.
                         `if __name__ == "__main__":` block: argparse --host/--port/--dev/--env-file,
                         then `uvicorn.run("app.main:app", ..., workers=1)` (import-string, so
                         --dev/reload works)
  auth.py                AuthManager: cookie-based session auth (scrypt + JWT), brute-force protection
  models.py              Pydantic v2: Rule, ImapConfig, SpamAssassinConfig, SchedulerConfig,
                         LoggingConfig, UiConfig (auto_refresh_enabled, auto_refresh_seconds), State + Enums.
                         All snake_case Python attributes; `model_config = ConfigDict(alias_generator=to_camel,
                         populate_by_name=True)` keeps SQLite JSON storage camelCase (see Key invariants)
  tz.py                  get_timezone(): resolves TZ env var via zoneinfo, falls back to UTC + logs warning
  db.py                  Db: SQLite CRUD, WAL mode, auto-migrates legacy JSON on first run
  processing.py          ProcessingService: IMAP fetch → spam check → rule eval → action (sync)
  spamassassin.py        SpamAssassinClient: raw SPAMC/1.5 socket protocol
  scheduler.py           SchedulerService: asyncio loop, runs ProcessingService in thread executor
  ui/
    theme.py             _page_setup() / _header() / _footer() / base_layout() context manager:
                         icon-nav header (bg-primary), dark/light toggle, no sidebar
    components.py        metric_card(), status_badge() reusable NiceGUI components
    pages/
      status.py          @ui.page("/")  — scheduler status + Run Now button, auto-refresh (UiConfig.auto_refresh_enabled / auto_refresh_seconds)
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

Note: `data/mailmanager.*` filenames and the `mailmanager_session` cookie name are product identity, not tied to the Python package name — they don't change if the package layout changes.

### Processing flow (ProcessingService.process_account)

1. Connect to IMAP; fetch UIDs after `last_processed_uid` for the current `uid_validity`
2. Chunk UIDs (50/batch); optionally check each via SpamAssassin
3. For spam: apply SpamAction (DELETE / MOVE / MARK)
4. For non-spam: evaluate rules sequentially; first match wins; any matched rule breaks the chain (`STOP` action matches and does nothing else)
5. Rules are filtered by `imap_config_name` — only rules matching the current account are evaluated
6. Actions: MOVE, COPY, DELETE, MARK_READ, MARK_UNREAD, FLAG, ADD_LABEL, REMOVE_LABEL, ARCHIVE, FORWARD (lazy SMTP pool)
7. DELETE/MOVE/ARCHIVE flag messages `\Deleted`; a single `expunge()` runs at end of account if any deletes occurred
8. Persist `last_processed_uid` after each chunk; on per-message exception, chunk aborts at that UID

### Scheduler (scheduler.py)

- `SchedulerService._loop()` runs as an asyncio task (created inside FastAPI `lifespan`)
- Sleep is interruptible: `asyncio.wait_for(_run_now_event.wait(), timeout=interval_seconds)`
- `scheduler.trigger_run_now()` (called from the Status page's Run Now button) sets `_run_now_event`; returns `False` if already running
- `SchedulerService.stop()` sets both `_stop_event` and `_run_now_event` so it returns promptly even mid-sleep, instead of blocking up to `interval_seconds` (see `tests/test_scheduler.py`)
- `ProcessingService` constructed fresh each run — picks up latest rules/config automatically
- Interval changes take effect on the next cycle (no reload needed)

### Rule evaluation

`_get_value_to_check()` extracts FROM / SUBJECT / TO / CC / BCC / MESSAGE (text/plain only).
`_evaluate_rule()` supports: EQUALS, CONTAINS, STARTS_WITH, ENDS_WITH, REGEX, NOT_EQUALS, NOT_CONTAINS.

### Persistence (db.py)

- Tables: `imap_configs` (name PK), `rules` (id AUTOINCREMENT PK), `spam_config` (id=1), `scheduler_config` (id=1), `logging_config` (id=1), `ui_config` (id=1), `states` (key PK)
- WAL mode + busy_timeout=5000ms to handle concurrent UI writes during scheduler runs
- State key format: `{account_name}:{folder}` → JSON `{uidValidity, lastProcessedUid}` (camelCase in storage via `alias_generator=to_camel`; Python attrs are `uid_validity`/`last_processed_uid`, see Key invariants)
- Auto-migration: if legacy JSON files exist in `data/`, they are imported and deleted atomically

## Deployment

- Registry: `ghcr.io/daniloreddy/mailmanager`
- CI/CD: `.github/workflows/docker-publish.yml` (triggers on push to main and tags)
- Volumes: `/app/data` (SQLite + lock)
- Env vars: `REQUIRE_AUTH`, `HOST`, `BIND_HOST`, `PORT`, `AUTH_SECURE_COOKIE`, `TRUSTED_PROXIES`, `TZ` (see Key invariants)
- `docker-compose.yml`/`docker-compose-dev.yml` set `TZ=${TZ:-UTC}` (container clock), `HOST=0.0.0.0` (fixed — container-internal bind, see Key invariants), `PORT=${PORT:-8080}`, and `NICEGUI_STORAGE_PATH=/app/data/.nicegui` (persists `app.storage.user`, e.g. dark mode, across container recreation); `docker-compose.yml` additionally publishes `ports:` at `${BIND_HOST:-127.0.0.1}:${PORT}:${PORT}` — deployer's choice for external reachability, unrelated to the container-internal `HOST`

## Key invariants

- Single instance enforced via `data/mailmanager.lock` (portalocker); acquired/released inside the FastAPI `lifespan` in `app/main.py` (not in `__main__`), so it's held whether the process starts via Docker's `uvicorn app.main:app` or local `python -m app.main` — importing the module alone (e.g. in tests) does NOT acquire it
- `--dev`/reload: uvicorn respawns a worker process per file change, which re-acquires the lock on import. The old worker normally releases before the new one starts, but a brief overlap (and a transient lock failure) is a known, accepted limitation of combining single-instance-lock with reload — not something to engineer around
- `workers=1` is passed explicitly to `uvicorn.run()` in `app/main.py` — multiple workers would spawn multiple scheduler loops and multiple lock holders; documented here rather than relying on the uvicorn default
- `MAILMANAGER_DATA_DIR` (default `data`) overrides where `Db`, the lock file, `auth.json`, `storage_secret`, and the rotating log live — exists so tests can point at a temp dir instead of the real `data/` directory (see `tests/test_main.py`)
- SpamAssassin client is fail-open: socket errors → treat as non-spam
- SMTP connection is lazy and pooled per `process_account` call
- `text/html` email bodies are NOT matched by MESSAGE rules (only `text/plain` decoded)
- STOP action type halts the rule chain but takes no action on the message
- Pydantic models use snake_case Python attributes; SQLite JSON columns stay camelCase via `model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)` on each model plus `model_dump(by_alias=True)` in `db.py`'s save methods — e.g. `rule.imap_config_name` in Python, `"imapConfigName"` in storage. Loads (`Model(**json.loads(...))`) work unmodified since Pydantic validates aliases by default
- Auth and network binding are independent settings, deliberately decoupled: `REQUIRE_AUTH` (unset/false → no login; true → cookie session via AuthManager gates the whole app) does not influence `HOST`. `HOST` (default `127.0.0.1`, non-Docker runs only — read directly via `os.environ`, see `app/main.py`) is a pure deployer choice: e.g. `HOST=0.0.0.0` with no auth on a trusted LAN, or `HOST=127.0.0.1` (default) with `REQUIRE_AUTH=true` behind a Cloudflare/SSH tunnel exposed to the internet. In Docker, `HOST=0.0.0.0` is fixed unconditionally inside the container (container's own `127.0.0.1` is unreachable from the host — Docker-networking necessity, not a deployer choice); external reachability there is controlled separately by `BIND_HOST` on the Compose `ports:` mapping. If this invariant ever regresses (`HOST` re-coupled to `REQUIRE_AUTH`), it's a bug — see `uvicorn.md` §2 in global guidelines: "HOST is a deployer choice, not an application concern"
- NiceGUI is mounted at `/ui` (`ui.run_with(app, mount_path="/ui", ...)`), so `/ui/*` is the cookie-protected surface while `/login`, `/auth/*`, `/health` stay public FastAPI routes at root. `_BYPASS_PREFIXES` excludes `/ui/_nicegui` (verified at runtime against the installed NiceGUI 3.13.0: covers both `/ui/_nicegui_ws` websocket and `/ui/_nicegui/<version>/...` static/library/component assets) from the cookie middleware — re-verify this prefix if the pinned NiceGUI version changes, since it's not guaranteed stable across major versions
- No separate REST API: earlier versions exposed a Bearer-token `/api/*` surface for the pre-NiceGUI vanilla-JS frontend; removed after the NiceGUI migration left it with zero consumers (NiceGUI pages talk to `Db`/`SchedulerService` in-process)
- `/health` is always public (in `_PUBLIC_PATHS`), regardless of `REQUIRE_AUTH`
- Logging: always stdout (captured by `docker compose logs`); local (non-Docker) runs also get a `RotatingFileHandler` at `data/mailmanager.log` — detected via `Path("/.dockerenv").exists()`; level change via Settings page takes effect immediately
- `.env` is loaded via `python-dotenv` at the top of `app/main.py` (`--env-file` CLI flag, else nearest `.env`), before `.auth`/other project modules are imported — those modules read `REQUIRE_AUTH`/`TRUSTED_PROXIES` from `os.environ` at import time, so load order matters
- `TZ` (IANA name, e.g. `Europe/Rome`) drives UI timestamp display via `app/tz.py`; unset/invalid → UTC with a logged warning. It's a boot-time-only setting like `PORT`/`HOST`, not part of the hot-reloadable `UiConfig`

## UI Guidelines

- Pages showing aggregated data or summaries (dashboards, status views) **must** support auto-refresh via `ui.timer`.
- Auto-refresh is controlled by a **single global** `UiConfig` stored in `ui_config` (id=1):
  - `auto_refresh_enabled: bool` — whether auto-refresh is active
  - `auto_refresh_seconds: int` — interval in seconds (min 1)
- Both fields are exposed in Settings → "Interfaccia" card (badge `hot-reload`). Changing them takes effect on the next page load.
- Every dashboard page must show a refresh label (always visible, right-aligned, `text-caption text-grey-6`), updated inside the refresh callback:
  - enabled → `Aggiornato: HH:MM:SS · auto-refresh Xs`
  - disabled → `auto-refresh disabilitato` (and no `ui.timer` is created)
- Pattern for every dashboard page: see `pages/status.py` (refreshable content + refresh label + conditional `ui.timer`).
- `UiConfig` is app-wide: one setting governs all dashboard pages simultaneously.
