import argparse
import asyncio
import os
import secrets
import sys
from collections.abc import AsyncGenerator, Callable
from contextlib import asynccontextmanager
from pathlib import Path

from dotenv import find_dotenv, load_dotenv


def _resolve_env_path() -> Path:
    # Precedence: ENV_FILE (Docker bind-mount) > --env-file CLI flag (local dev)
    # > nearest .env found from cwd.
    env_file_var = os.environ.get("ENV_FILE")
    if env_file_var:
        return Path(env_file_var)
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument("--env-file", type=str, default=None)
    args, _ = parser.parse_known_args()
    if args.env_file:
        return Path(args.env_file)
    found = find_dotenv(usecwd=True)
    return Path(found) if found else Path(".env")


# Stage 1 — lightweight parse at import time, before any other env var is read
# (REQUIRE_AUTH / TRUSTED_PROXIES are read at import time further down / in .auth).
load_dotenv(_resolve_env_path())

import logging  # noqa: E402
from logging.handlers import RotatingFileHandler  # noqa: E402

logging.basicConfig(level=logging.INFO)  # noqa: E402

import portalocker  # noqa: E402
from fastapi import FastAPI, Form, Request, Response  # noqa: E402
from fastapi.responses import FileResponse, RedirectResponse  # noqa: E402

from .db import Db  # noqa: E402
from .models import LoggingConfig  # noqa: E402
from .scheduler import SchedulerService  # noqa: E402

logger = logging.getLogger(__name__)

# python:3.12-slim (our base image) always creates this file — reliable Docker signal.
_IS_DOCKER = Path("/.dockerenv").exists()

# Overridable so tests can point at a temp dir instead of the real data/ directory
# (uvicorn.md §6: never point a test at the project's real data/ directory).
_DATA_DIR = Path(os.environ.get("MAILMANAGER_DATA_DIR", "data"))

_STATIC_ROOT = Path(__file__).parent.parent / "static"
_PUBLIC_PATHS = {"/health", "/login", "/auth/login", "/auth/logout"}
_BYPASS_PREFIXES = ("/ui/_nicegui",)

_require_auth: bool = os.environ.get("REQUIRE_AUTH", "false").lower() in (
    "true",
    "1",
    "yes",
)


def _is_secure(headers: dict) -> bool:
    if os.environ.get("AUTH_SECURE_COOKIE") == "1":
        return True
    return headers.get("x-forwarded-proto") == "https"


def _load_storage_secret(data_dir: Path) -> str:
    # Stable across restarts so app.storage.user (e.g. dark mode) persists.
    secret_file = data_dir / "storage_secret"
    if secret_file.exists():
        secret = secret_file.read_text(encoding="utf-8").strip()
        if secret:
            return secret
    secret = secrets.token_hex(32)
    data_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
    secret_file.write_text(secret, encoding="utf-8")
    return secret


def _configure_logging(cfg: LoggingConfig) -> None:
    root = logging.getLogger()
    root.setLevel(cfg.level.value)
    root.handlers.clear()

    formatter = logging.Formatter("%(asctime)s - %(name)s - %(levelname)s - %(message)s")

    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(formatter)
    root.addHandler(handler)

    if not _IS_DOCKER:
        # Docker logs are captured via `docker compose logs`; local runs get a
        # rotating file too so a closed terminal doesn't lose the daemon's history.
        log_dir = _DATA_DIR
        log_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
        file_handler = RotatingFileHandler(
            log_dir / "mailmanager.log",
            maxBytes=5 * 1024 * 1024,
            backupCount=3,
            encoding="utf-8",
        )
        file_handler.setFormatter(formatter)
        root.addHandler(file_handler)


auth = None
if _require_auth:
    from .auth import AuthManager

    auth = AuthManager(
        auth_file=_DATA_DIR / "auth.json",
        cookie_name="mailmanager_session",
    )
    if not auth.has_password():
        logger.warning("No password set. Run: python scripts/set_password.py")

_lock: portalocker.Lock | None = None


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncGenerator[None, None]:
    global _lock

    lock_file = _DATA_DIR / "mailmanager.lock"
    lock_file.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    _lock = portalocker.Lock(lock_file, timeout=1)
    try:
        _lock.acquire()
    except portalocker.exceptions.LockException:
        logger.error("MailManager is already running.")
        raise

    db = Db(str(_DATA_DIR))
    _configure_logging(db.load_logging_config())

    scheduler = SchedulerService(db)

    # NiceGUI page handlers receive request.app = nicegui's core.app (not this app),
    # so we attach db/scheduler to nicegui's app.state for page handler access.
    from nicegui import app as nicegui_app

    nicegui_app.state.db = db
    nicegui_app.state.scheduler = scheduler

    scheduler.start()
    purge_task: asyncio.Task | None = None
    if auth is not None:
        purge_task = asyncio.create_task(auth.purge_loop())

    yield

    await scheduler.stop()
    if purge_task is not None:
        purge_task.cancel()
    _lock.release()


app = FastAPI(title="MailManager", lifespan=lifespan)


@app.get("/health")
async def health() -> dict:
    return {"status": "ok"}


if auth is not None:
    # Local binding so mypy narrows `AuthManager | None` to `AuthManager` inside
    # these closures — narrowing on the module-level `auth` global doesn't persist
    # into nested function bodies.
    _auth: AuthManager = auth

    @app.middleware("http")
    async def _auth_gate(request: Request, call_next: Callable) -> Response:
        path = request.url.path
        if path in _PUBLIC_PATHS or any(path.startswith(p) for p in _BYPASS_PREFIXES):
            return await call_next(request)
        token = request.cookies.get(_auth.cookie_name, "")
        if _auth.verify_token(token):
            return await call_next(request)
        return RedirectResponse(url="/login", status_code=302)

    @app.get("/login")
    async def login_page() -> FileResponse:
        return FileResponse(_STATIC_ROOT / "login.html")

    @app.post("/auth/login")
    async def auth_login(request: Request, password: str = Form(...)) -> Response:
        ip = _auth.client_ip(
            dict(request.headers),
            request.client.host if request.client else None,
        )
        success, _ = _auth.attempt_login(password, ip)
        if not success:
            return RedirectResponse(url="/login?error=1", status_code=302)
        token = _auth.create_token()
        redirect = RedirectResponse(url="/ui", status_code=302)
        _auth.set_cookie(redirect, token, _is_secure(dict(request.headers)))
        return redirect

    @app.get("/auth/logout")
    async def auth_logout() -> Response:
        redirect = RedirectResponse(url="/login", status_code=302)
        _auth.clear_cookie(redirect)
        return redirect


# Register NiceGUI pages and mount into FastAPI app
from nicegui import ui  # noqa: E402

from .ui import pages as _ui_pages  # noqa: E402,F401 — triggers @ui.page registrations

ui.run_with(app, mount_path="/ui", storage_secret=_load_storage_secret(_DATA_DIR))


if __name__ == "__main__":
    default_port = int(os.environ.get("PORT", "8080"))
    default_host = os.environ.get("HOST", "127.0.0.1")
    default_dev = os.environ.get("DEV", "false").lower() in ("true", "1", "yes")

    parser = argparse.ArgumentParser(description="MailManager server")
    parser.add_argument("--port", type=int, default=default_port)
    parser.add_argument("--host", type=str, default=default_host)
    parser.add_argument("--dev", action="store_true", default=default_dev)
    # Already consumed by _resolve_env_path() above; kept here so --help/argparse
    # doesn't reject it when passed on the command line.
    parser.add_argument("--env-file", type=str, default=None)
    args = parser.parse_args()

    import uvicorn

    logger.info(f"Starting MailManager on {args.host}:{args.port}")
    uvicorn.run(
        "app.main:app",
        host=args.host,
        port=args.port,
        reload=args.dev,
        workers=1,  # mandatory: in-process scheduler + portalocker lock aren't
        # shared across worker processes — see CLAUDE.md Key invariants.
    )
