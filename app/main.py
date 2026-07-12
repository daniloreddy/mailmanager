from dotenv import load_dotenv
from redberry_webkit.env_resolver import resolve_env_path

_env_path = resolve_env_path()
load_dotenv(_env_path)

import argparse  # noqa: E402
import asyncio  # noqa: E402
import logging  # noqa: E402
import os  # noqa: E402
import sys  # noqa: E402
from collections.abc import AsyncGenerator, Callable  # noqa: E402
from contextlib import asynccontextmanager  # noqa: E402
from logging.handlers import RotatingFileHandler  # noqa: E402
from pathlib import Path  # noqa: E402

import portalocker  # noqa: E402
from fastapi import FastAPI, Request, Response  # noqa: E402
from fastapi.responses import RedirectResponse  # noqa: E402
from redberry_webkit.auth import client_ip, purge_loop  # noqa: E402
from redberry_webkit.logging_utils import CredentialFilter  # noqa: E402
from slowapi import Limiter, _rate_limit_exceeded_handler  # noqa: E402
from slowapi.errors import RateLimitExceeded  # noqa: E402
from slowapi.middleware import SlowAPIMiddleware  # noqa: E402

from .config import config  # noqa: E402
from .db import Db, migrate_legacy_config_to_env  # noqa: E402
from .metrics import metrics  # noqa: E402
from .scheduler import SchedulerService  # noqa: E402
from .ui.router import TRUSTED_PROXIES, auth  # noqa: E402
from .ui.router import router as ui_router  # noqa: E402

logger = logging.getLogger(__name__)

# python:3.12-slim (our base image) always creates this file — reliable Docker signal.
_IS_DOCKER = Path("/.dockerenv").exists()

# Overridable so tests can point at a temp dir instead of the real data/ directory
# (uvicorn.md §6: never point a test at the project's real data/ directory).
_DATA_DIR = Path(os.environ.get("MAILMANAGER_DATA_DIR", "data"))

_PUBLIC_PATHS = {"/health", "/login", "/auth/login", "/auth/logout"}
_BYPASS_PREFIXES = ("/ui/_nicegui",)

DEV = os.environ.get("DEV", "false").lower() in ("true", "1", "yes")
CONFIG_RELOAD_INTERVAL_S = 5

_stream_handler = logging.StreamHandler(sys.stdout)
_credential_filter = CredentialFilter()
_stream_handler.addFilter(_credential_filter)
_stream_handler.setFormatter(
    logging.Formatter("%(asctime)s - %(name)s - %(levelname)s - %(message)s")
)

_handlers: list[logging.Handler] = [_stream_handler]

if not _IS_DOCKER:
    # Docker logs are captured via `docker compose logs`; local runs get a rotating
    # file too so a closed terminal doesn't lose the daemon's history.
    _DATA_DIR.mkdir(parents=True, exist_ok=True, mode=0o700)
    _file_handler = RotatingFileHandler(
        _DATA_DIR / "mailmanager.log", maxBytes=5 * 1024 * 1024, backupCount=3, encoding="utf-8"
    )
    _file_handler.addFilter(_credential_filter)
    _file_handler.setFormatter(
        logging.Formatter("%(asctime)s - %(name)s - %(levelname)s - %(message)s")
    )
    _handlers.append(_file_handler)

logging.basicConfig(level=config.get("LOG_LEVEL", "INFO"), handlers=_handlers, force=True)
logger.info("Using .env=%s", _env_path)


def _rate_limit_key(request: Request) -> str:
    host = request.client.host if request.client else "unknown"
    return client_ip(request.headers, host, TRUSTED_PROXIES)


limiter = Limiter(key_func=_rate_limit_key)

_lock: portalocker.Lock | None = None


async def _config_reload_loop(interval_s: int) -> None:
    while True:
        await asyncio.sleep(interval_s)
        if config.reload_if_stale():
            logging.getLogger().setLevel(config.get("LOG_LEVEL", "INFO"))


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
    migrate_legacy_config_to_env(_DATA_DIR, config)

    await metrics.init_db()

    scheduler = SchedulerService(db)

    # NiceGUI page handlers receive request.app = nicegui's core.app (not this app),
    # so we attach db/scheduler/metrics to nicegui's app.state for page handler access.
    from nicegui import app as nicegui_app

    nicegui_app.state.db = db
    nicegui_app.state.scheduler = scheduler
    nicegui_app.state.metrics = metrics

    scheduler.start()
    purge_task = asyncio.create_task(purge_loop(auth))
    config_task = asyncio.create_task(_config_reload_loop(CONFIG_RELOAD_INTERVAL_S))

    yield

    await scheduler.stop()
    purge_task.cancel()
    config_task.cancel()
    _lock.release()


app = FastAPI(
    title="MailManager",
    lifespan=lifespan,
    docs_url="/docs" if DEV else None,
    redoc_url="/redoc" if DEV else None,
    openapi_url="/openapi.json" if DEV else None,
)
app.state.limiter = limiter
# slowapi lacks precise stubs for this handler signature, hence the ignore below.
app.add_exception_handler(RateLimitExceeded, _rate_limit_exceeded_handler)  # type: ignore[arg-type]
app.add_middleware(SlowAPIMiddleware)
app.include_router(ui_router)


@app.get("/health")
async def health() -> dict:
    return {"status": "ok"}


@app.get("/")
async def root() -> RedirectResponse:
    return RedirectResponse(url="/ui/")


@app.middleware("http")
async def _auth_gate(request: Request, call_next: Callable) -> Response:
    path = request.url.path
    if path in _PUBLIC_PATHS or any(path.startswith(p) for p in _BYPASS_PREFIXES):
        return await call_next(request)
    token = request.cookies.get(auth.cookie_name, "")
    if auth.verify_token(token):
        return await call_next(request)
    return RedirectResponse(url="/login", status_code=302)


# Register NiceGUI pages and mount into FastAPI app
from nicegui import ui  # noqa: E402

from .ui import pages as _ui_pages  # noqa: E402,F401 — triggers @ui.page registrations

ui.run_with(app, mount_path="/ui", storage_secret=auth.ui_storage_secret)


if __name__ == "__main__":
    default_port = int(os.environ.get("PORT", "8080"))
    default_host = os.environ.get("HOST", "127.0.0.1")

    parser = argparse.ArgumentParser(description="MailManager server")
    parser.add_argument("--port", type=int, default=default_port)
    parser.add_argument("--host", type=str, default=default_host)
    parser.add_argument("--dev", action=argparse.BooleanOptionalAction, default=DEV)
    # Already consumed by resolve_env_path() above; kept here so --help/argparse
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
        reload_dirs=[
            str(Path(__file__).resolve().parent),
            str(Path(__file__).resolve().parent.parent / "static"),
        ]
        if args.dev
        else None,
        workers=1,  # mandatory: in-process scheduler + portalocker lock aren't
        # shared across worker processes — see CLAUDE.md Key invariants.
    )
