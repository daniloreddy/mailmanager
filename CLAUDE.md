# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

IMAP rule-based email sorter with SpamAssassin integration. Runs as a long-lived daemon: FastAPI server + NiceGUI web UI + background scheduler. UI served by NiceGUI mounted into FastAPI via `ui.run_with(app, mount_path="/ui", ...)` — the whole dashboard lives under `/ui`; `/login`, `/auth/*`, `/health` stay FastAPI routes at root.

Scaffolded from [`redberry-webapp-template`](https://github.com/daniloreddy/redberry-webapp-template) (`.copier-answers.yml` at repo root, `app_slug: mailmanager`) and built on [`redberry-webkit`](https://github.com/daniloreddy/redberry-webkit) (auth, env resolver, config, logging redaction, timezone, metrics) — see "Post-upgrade migration" below for the one-time step required on any pre-existing deployment. This project's substantial pre-existing business logic (`processing.py`, `scheduler.py`, `spamassassin.py`, `db.py`, 4 NiceGUI pages) was hand-ported into the generated scaffold rather than laid down by `copier copy` directly, so a future `copier update` will still need manual conflict resolution on `main.py`/`router.py` — the answers file enables `copier update`'s bookkeeping, not a conflict-free auto-merge.

## Commands

```bash
# Setup (Windows: venv | Linux/Mac: .venv)
python -m venv venv
./venv/Scripts/pip install -r requirements.txt -r requirements.dev.txt

# Run
./venv/Scripts/python -m app.main          # binds 127.0.0.1:8080, auth required; loads ./.env if present
HOST=0.0.0.0 ./venv/Scripts/python -m app.main   # reachable beyond localhost, auth still required
PORT=9000 ./venv/Scripts/python -m app.main        # custom port
./venv/Scripts/python -m app.main --env-file .env.prod  # load a specific .env file
./venv/Scripts/python -m app.main --host 0.0.0.0 --port 9000 --dev  # CLI flags override env vars; --dev enables uvicorn reload, /docs, /redoc

# Lint + type check + tests
scripts/checks.bat                     # Windows (ruff check/format + mypy + pytest)
scripts/checks.sh                      # Unix

# Tests
./venv/Scripts/pytest tests/                    # all tests
./venv/Scripts/pytest tests/test_db.py          # single file
./venv/Scripts/pytest tests/test_processing.py::test_name  # single test

# First-run / post-upgrade: set the login password
./venv/Scripts/python scripts/set_password.py
```

## Architecture

All production code lives under `app/` — entrypoint, business logic, and UI (per `uvicorn.md` §3 in the global guidelines: no sibling top-level package for code the running app imports; only `tests/`, `static/`, `scripts/`, `data/` may live outside `app/`).

```
app/
  main.py                Entrypoint AND FastAPI app — module-level `app = FastAPI(...)`, no separate
                         factory. resolve_env_path()/.env (redberry_webkit.env_resolver, before any
                         other project import) → logging.basicConfig (stdout + rotating file, both
                         through redberry_webkit's CredentialFilter) → lifespan acquires the
                         portalocker lock and constructs Db/SchedulerService, runs the one-time
                         migrate_legacy_config_to_env() (see "Post-upgrade migration"), starts
                         metrics.init_db() and the ConfigManager reload loop (so this runs both under
                         Docker's `uvicorn app.main:app` and local `python -m app.main`, not only
                         behind `__main__`) → routes/middleware (health, cookie auth gate) → includes
                         app/ui/router.py's APIRouter (/login, /auth/login, /auth/logout) → NiceGUI
                         mount via ui.run_with(). No separate REST API — NiceGUI pages read/write Db/
                         SchedulerService/MetricsStore directly in-process via nicegui.app.state.
                         slowapi Limiter is wired (app.state.limiter, RateLimitExceeded handler) but
                         idle — no route uses @limiter.limit, kept available for future use, not a
                         reintroduction of a REST API.
                         GET /health — public, unauthenticated liveness check.
                         GET / — redirects to /ui/ (auth-gated like any other non-public path,
                         so unauthenticated visitors land on /login first).
                         `if __name__ == "__main__":` block: argparse --host/--port/--dev/--env-file,
                         then `uvicorn.run("app.main:app", ..., workers=1)` (import-string, so
                         --dev/reload works)
  config.py              ConfigManager (redberry_webkit.config), .env-backed, hot-reload via mtime
                         polling (~5s, see main.py's _config_reload_loop). Holds every single-instance
                         scalar setting that used to be a one-row Db table: REFRESH_ENABLED/
                         REFRESH_INTERVAL (ex UiConfig), SCHEDULER_ENABLED/SCHEDULER_INTERVAL_SECONDS
                         (ex SchedulerConfig), LOG_LEVEL (ex LoggingConfig), SPAM_* (ex
                         SpamAssassinConfig). imap_configs (multiple IMAP accounts) and rules (a list)
                         stay in Db — structured, multi-row data has no fit in a flat key-value store.
  metrics.py             MetricsStore (redberry_webkit.metrics), SQLite-backed (data/metrics.db).
                         Records one entry per IMAP account per scheduler run (status ok/error,
                         duration_s, error_message, extra={"account": name}) — see scheduler.py. Not
                         a REST-API request-metrics use case; repurposed for scheduler run history,
                         which previously had no persistence (only in-memory SchedulerStatus).
  models.py              Pydantic v2: Rule, ImapConfig, SpamAssassinConfig (now constructed on the fly
                         from ConfigManager values, not persisted via Db), LoggingLevel enum, State.
                         All snake_case Python attributes; `model_config = ConfigDict(alias_generator=to_camel,
                         populate_by_name=True)` keeps SQLite JSON storage camelCase (see Key invariants)
  db.py                  Db: SQLite CRUD for imap_configs/rules/states only, WAL mode, auto-migrates
                         legacy JSON on first run. Also hosts migrate_legacy_config_to_env() — the
                         one-time Db→`.env` migration for the four removed config tables (see
                         "Post-upgrade migration").
  processing.py          ProcessingService: IMAP fetch → spam check → rule eval → action (sync)
  spamassassin.py        SpamAssassinClient: raw SPAMC/1.5 socket protocol
  scheduler.py           SchedulerService: asyncio loop, runs ProcessingService in thread executor,
                         records one MetricsRecord per account per run, purges metrics >30 days old
  ui/
    router.py             APIRouter: /login, /auth/login, /auth/logout. Instantiates
                          `redberry_webkit.auth.AuthManager` (module-level `auth`, `TRUSTED_PROXIES`)
                          — replaces the old project-local app/auth.py. Login route orchestrates the
                          decomposed AuthManager API explicitly (has_password → is_global_limited →
                          is_ip_blocked → verify_password + record_attempt), unlike the old single
                          attempt_login() method.
    theme.py             _page_setup() / _header() / _footer() / base_layout() context manager:
                         icon-nav header (bg-primary), dark/light toggle, no sidebar
    components.py        metric_card(), status_badge() reusable NiceGUI components
    pages/
      status.py          @ui.page("/")  — scheduler status + Run Now button + persistent "Storico
                         run" table (MetricsStore.get_history()), auto-refresh (config REFRESH_ENABLED
                         / REFRESH_INTERVAL). Timezone via redberry_webkit.timezone_utils.resolve_timezone()
                         (replaces the old app/tz.py).
      imap.py            @ui.page("/imap") — IMAP config CRUD (unchanged, still Db-backed)
      rules.py           @ui.page("/rules") — rule CRUD (unchanged, still Db-backed)
      settings.py        @ui.page("/settings") — spam + scheduler + logging + Interfaccia (auto-refresh)
                         config, all read/written via ConfigManager.get_*/update_many instead of Db
scripts/
  checks.bat / checks.sh Lint + type check + tests (ruff, mypy, pytest); auto-creates venv
  run.bat / run.sh       Run server locally; auto-creates venv
  set_password.py        CLI to set login password (re-execs into existing venv, no auto-init; also
                         runnable in Docker). Bootstrap probes `redberry_webkit.auth` importability
                         (not a local app.auth module, which no longer exists) and imports the
                         already-constructed `auth` singleton from app.ui.router.
static/
  login.html             Self-contained login page (always used — auth is unconditional on /ui)
data/
  mailmanager.db         SQLite (imap_configs, rules, states — the four single-row config tables are
                         gone; a pre-upgrade db file may still physically contain their empty/stale
                         tables, harmless, never read again except by the one-time migration)
  metrics.db             SQLite (MetricsStore) — scheduler run history, auto-purged after 30 days
  auth.json              password_hash + salt + JWT secret + ui_storage_secret, all in redberry_webkit.auth's
                         schema (see "Post-upgrade migration" for the pre-upgrade schema difference)
  mailmanager.lock       portalocker single-instance guard
  mailmanager.log        RotatingFileHandler output, non-Docker runs only (5MB x 3 backups)
  .config_migrated_from_db  marker file — presence means migrate_legacy_config_to_env() already ran
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

Unchanged by the redberry-webkit migration — still free of FastAPI imports (uvicorn.md §3), still swallows/logs its own IMAP connection errors (fail-open at the account level). Because of that, `scheduler.py`'s per-account metrics recording can't always distinguish "processed fine" from "IMAP connect failed and was logged internally" — see scheduler.py below.

### Scheduler (scheduler.py)

- `SchedulerService._loop()` runs as an asyncio task (created inside FastAPI `lifespan`)
- Sleep is interruptible: `asyncio.wait_for(_run_now_event.wait(), timeout=interval_seconds)`
- `scheduler.trigger_run_now()` (called from the Status page's Run Now button) sets `_run_now_event`; returns `False` if already running
- `SchedulerService.stop()` sets both `_stop_event` and `_run_now_event` so it returns promptly even mid-sleep, instead of blocking up to `interval_seconds` (see `tests/test_scheduler.py`)
- `ProcessingService` constructed fresh each run — picks up latest rules/config automatically
- `enabled`/`interval_seconds` now come from `config.get_bool("SCHEDULER_ENABLED")`/`config.get_int("SCHEDULER_INTERVAL_SECONDS")` (ConfigManager, hot-reload ~5s) instead of a Db row — interval changes still take effect on the next cycle, now even faster than before
- After each account's `process_account()` call, `_run_sync()` records one `MetricsRecord` (status/duration_s/error_message/extra={"account": name}) via `MetricsStore.record()`; `_do_run()` calls `metrics.purge_old(days=30)` once per cycle. Because `process_account()` catches and logs its own IMAP/connection errors internally (see Processing flow above), only genuinely unexpected exceptions escaping the call surface as `status="error"` here — most real-world failures still show as `status="ok"` in the history table. This is a known, accepted limitation, not a bug to fix by changing `processing.py`'s error handling.

### Rule evaluation

`_get_value_to_check()` extracts FROM / SUBJECT / TO / CC / BCC / MESSAGE (text/plain only).
`_evaluate_rule()` supports: EQUALS, CONTAINS, STARTS_WITH, ENDS_WITH, REGEX, NOT_EQUALS, NOT_CONTAINS.

### Persistence (db.py + config.py)

- Db tables: `imap_configs` (name PK), `rules` (id AUTOINCREMENT PK), `states` (key PK) — that's all; the four single-row config tables (`spam_config`, `scheduler_config`, `logging_config`, `ui_config`) are gone, replaced by `.env`-backed `ConfigManager` (see `app/config.py`)
- WAL mode + busy_timeout=5000ms to handle concurrent UI writes during scheduler runs
- State key format: `{account_name}:{folder}` → JSON `{uidValidity, lastProcessedUid}` (camelCase in storage via `alias_generator=to_camel`; Python attrs are `uid_validity`/`last_processed_uid`, see Key invariants)
- Auto-migration: if legacy JSON files exist in `data/`, they are imported and deleted atomically (unchanged)
- `db.migrate_legacy_config_to_env()`: one-time, guarded by `data/.config_migrated_from_db` marker — reads any pre-existing values from the four now-removed tables (if a pre-upgrade `mailmanager.db` still has them) and folds them into `.env` via `config.update_many(...)` so an upgrade doesn't silently reset a deployment's settings back to `_DEFAULTS`. Wrapped in `try/except (OSError, ValueError, sqlite3.Error)` — never blocks boot, retries next start if it fails.

## Deployment

- Registry: `ghcr.io/daniloreddy/mailmanager`
- CI/CD: `.github/workflows/docker-publish.yml` (triggers on push to main and tags)
- Volumes: `/app/data` (SQLite + lock + metrics.db)
- Env vars: `HOST`, `PORT`, `AUTH_SECURE_COOKIE`, `TRUSTED_PROXIES`, `TZ`, `DEV` (boot-time-only, see Key invariants) plus the `ConfigManager`-backed runtime settings in `.env.example` (`REFRESH_*`, `SCHEDULER_*`, `LOG_LEVEL`, `SPAM_*`)
- Dockerfile installs `git` transiently (needed by pip to resolve the `redberry-webkit @ git+https://...` pin), purges it before the final image; runs as non-root `appuser`; `COPY` is selective (`app/`, `static/`, `scripts/` only — no `.git`/`data`/`tests` in the image)
- `docker-compose.yml`/`docker-compose-dev.yml` set `TZ=${TZ:-UTC}` (container clock), `PORT=${PORT:-8080}`, and `NICEGUI_STORAGE_PATH=/app/data/.nicegui` (persists `app.storage.user`, e.g. dark mode, across container recreation); the container-internal bind (`--host 0.0.0.0`) is hardcoded in the Dockerfile `CMD`, not read from env. `docker-compose.yml` publishes `ports:` at `${HOST:-127.0.0.1}:${PORT}:${PORT}` — same `HOST` var used for the bare-metal bind (uvicorn.md §2), reused here for the Compose publish address since native and Docker never run against the same `.env` at once

## Post-upgrade migration (redberry-webkit adoption) — required, one-time

This project was migrated from a fully local `app/auth.py`/`app/tz.py` + Db-backed settings implementation onto `redberry-webkit` + the `redberry-webapp-template` scaffold. Two consequences for any **pre-existing** deployment (skip this section for a fresh install):

1. **`data/auth.json` schema changed.** The old format stored `password_hash` as a combined `"salt_hex:hash_hex"` string; `redberry_webkit.auth.AuthManager` expects separate `salt`/`password_hash` fields. An old file loads without error, but every login attempt then fails silently — `has_password()` still returns `True` (the old combined string is truthy), so there is no diagnostic warning, just "Invalid password" forever. **Fix: re-run `scripts/set_password.py` once after upgrading** — this rewrites `auth.json` into the new schema. Do this before relying on login.
2. **`data/storage_secret` is gone**, folded into `auth.json`'s `ui_storage_secret` field (auto-added on first load if missing). Effect: NiceGUI's `app.storage.user` (dark/light mode preference) decrypts with a different secret once and silently resets to default — cosmetic, no action needed.
3. **Db-backed settings migrate automatically** on first boot after upgrading: `db.migrate_legacy_config_to_env()` reads any existing `spam_config`/`scheduler_config`/`logging_config`/`ui_config` rows and writes them into `.env` via `ConfigManager`, guarded by a `data/.config_migrated_from_db` marker so it only runs once. No manual action needed here, but if `.env` isn't writable at first boot, check the logs for a warning and fix permissions — it retries on the next start.

## Key invariants

- Single instance enforced via `data/mailmanager.lock` (portalocker); acquired/released inside the FastAPI `lifespan` in `app/main.py` (not in `__main__`), so it's held whether the process starts via Docker's `uvicorn app.main:app` or local `python -m app.main` — importing the module alone (e.g. in tests) does NOT acquire it
- `--dev`/reload: uvicorn respawns a worker process per file change, which re-acquires the lock on import. The old worker normally releases before the new one starts, but a brief overlap (and a transient lock failure) is a known, accepted limitation of combining single-instance-lock with reload — not something to engineer around
- `workers=1` is passed explicitly to `uvicorn.run()` in `app/main.py` — multiple workers would spawn multiple scheduler loops and multiple lock holders; documented here rather than relying on the uvicorn default
- `MAILMANAGER_DATA_DIR` (default `data`) overrides where `Db`, `MetricsStore`, the lock file, `auth.json`, and the rotating log live — exists so tests can point at a temp dir instead of the real `data/` directory (see `tests/test_main.py`). Respected by `app/main.py`, `app/ui/router.py`, and `app/metrics.py` independently (each reads the env var itself at import time) — `.env`-file resolution for `ConfigManager` is a **separate**, unrelated env var (`ENV_FILE`/`--env-file`, see `redberry_webkit.env_resolver`), so pointing `MAILMANAGER_DATA_DIR` at a temp dir does NOT redirect which `.env` `ConfigManager` reads — tests that touch `config.update_many(...)` must monkeypatch `config._cache` directly (see `tests/test_scheduler.py`) rather than relying on data-dir isolation
- SpamAssassin client is fail-open: socket errors → treat as non-spam
- SMTP connection is lazy and pooled per `process_account` call
- `text/html` email bodies are NOT matched by MESSAGE rules (only `text/plain` decoded)
- STOP action type halts the rule chain but takes no action on the message
- Pydantic models use snake_case Python attributes; SQLite JSON columns stay camelCase via `model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)` on each model plus `model_dump(by_alias=True)` in `db.py`'s save methods — e.g. `rule.imap_config_name` in Python, `"imapConfigName"` in storage. Loads (`Model(**json.loads(...))`) work unmodified since Pydantic validates aliases by default
- Auth on `/ui` is unconditional — cookie session via `AuthManager` always gates the whole dashboard, no opt-out. `HOST` (default `127.0.0.1`, non-Docker runs only — read directly via `os.environ`, see `app/main.py`) is a separate, independent deployer choice about which interface the server binds: e.g. `HOST=0.0.0.0` on a trusted LAN, or `HOST=127.0.0.1` (default) behind a Cloudflare/SSH tunnel exposed to the internet — either way, login is required. In Docker, `HOST=0.0.0.0` is fixed unconditionally inside the container (container's own `127.0.0.1` is unreachable from the host — Docker-networking necessity, not a deployer choice); external reachability there is controlled separately by `BIND_HOST` on the Compose `ports:` mapping. If this invariant ever regresses (a `REQUIRE_AUTH`-style toggle reintroduced to make auth optional), it's a bug — see `fastapi-auth.md` in global guidelines: "there is no REQUIRE_AUTH-style toggle... the cookie gate is always there"
- NiceGUI is mounted at `/ui` (`ui.run_with(app, mount_path="/ui", ...)`), so `/ui/*` is the cookie-protected surface while `/login`, `/auth/*`, `/health` stay public FastAPI routes at root. `_BYPASS_PREFIXES` excludes `/ui/_nicegui` (verified at runtime against the installed NiceGUI 3.13.0: covers both `/ui/_nicegui_ws` websocket and `/ui/_nicegui/<version>/...` static/library/component assets) from the cookie middleware — re-verify this prefix if the pinned NiceGUI version changes, since it's not guaranteed stable across major versions. Note this deliberately does NOT match `redberry-webapp-template`'s generated `main.py`, which bypasses `/ui/socket.io` — that value is wrong for NiceGUI 3.13.0 (whose websocket actually lives at `/_nicegui_ws/`) and would break the bypass for already-logged-in users if copied verbatim; the template's literal value is for a different NiceGUI version, not a pattern to follow blindly
- No separate REST API: earlier versions exposed a Bearer-token `/api/*` surface for the pre-NiceGUI vanilla-JS frontend; removed after the NiceGUI migration left it with zero consumers (NiceGUI pages talk to `Db`/`SchedulerService`/`MetricsStore` in-process). The slowapi `Limiter` wired in `main.py` is idle infrastructure for a possible future API, not evidence of one existing today
- `/health` is always public (in `_PUBLIC_PATHS`)
- Logging: always stdout (captured by `docker compose logs`, filtered through `redberry_webkit.logging_utils.CredentialFilter`); local (non-Docker) runs also get a `RotatingFileHandler` at `data/mailmanager.log` (same filter) — detected via `Path("/.dockerenv").exists()`; level (`LOG_LEVEL` in `ConfigManager`) change via Settings page takes effect immediately, and is also picked up automatically by the ~5s config reload loop for out-of-band `.env` edits
- `.env` is resolved via `redberry_webkit.env_resolver.resolve_env_path()` at the top of `app/main.py` (`ENV_FILE` > `--env-file` CLI flag > nearest `.env`), before `app.ui.router`/other project modules are imported — `app.ui.router` reads `TRUSTED_PROXIES` from `os.environ` at import time, so load order matters
- `TZ` (IANA name, e.g. `Europe/Rome`) drives UI timestamp display via `redberry_webkit.timezone_utils.resolve_timezone()` (called with `os.environ.get("TZ", "UTC")` in `app/ui/pages/status.py`); unset/invalid → UTC with a logged warning. It's a boot-time-only setting like `PORT`/`HOST`/`DEV`, not part of the hot-reloadable `ConfigManager` settings

## UI Guidelines

- Pages showing aggregated data or summaries (dashboards, status views) **must** support auto-refresh via `ui.timer`.
- Auto-refresh is controlled by a **single global** pair of `ConfigManager` keys (`.env`-backed, hot-reload ~5s — formerly the Db-backed `UiConfig` table):
  - `REFRESH_ENABLED` (bool) — whether auto-refresh is active
  - `REFRESH_INTERVAL` (int, seconds, min 1)
- Both fields are exposed in Settings → "Interfaccia" card (badge `hot-reload`), read via `config.get_bool("REFRESH_ENABLED")`/`config.get_int("REFRESH_INTERVAL", 30)` and written via `config.update_many({...})`.
- Every dashboard page must show a refresh label (always visible, right-aligned, `text-caption text-grey-6`), updated inside the refresh callback:
  - enabled → `Aggiornato: HH:MM:SS · auto-refresh Xs`
  - disabled → `auto-refresh disabilitato` (and no `ui.timer` is created)
- Pattern for every dashboard page: see `pages/status.py` (refreshable content + refresh label + conditional `ui.timer`).
- These two keys are app-wide: one setting governs all dashboard pages simultaneously.
