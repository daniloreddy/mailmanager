import asyncio
import logging
import os
import secrets
from contextlib import asynccontextmanager
from pathlib import Path
from typing import AsyncGenerator, Callable, Optional

from fastapi import Depends, FastAPI, Form, HTTPException, Request, Response
from fastapi.responses import FileResponse, RedirectResponse
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from .db import Db
from .scheduler import SchedulerService
from .api import configs, logging_config, rules, scheduler as scheduler_routes, spam

logger = logging.getLogger(__name__)

_api_key: Optional[str] = os.environ.get("MAILMANAGER_API_KEY")
_bearer = HTTPBearer(auto_error=False)

_STATIC_ROOT = Path(__file__).parent.parent / "static"
_PUBLIC_PATHS = {"/login", "/auth/login", "/auth/logout"}
_BYPASS_PREFIXES = ("/_nicegui/", "/api/")


def verify_token(
    credentials: HTTPAuthorizationCredentials | None = Depends(_bearer),
) -> None:
    if _api_key is None:
        return
    if credentials is None or credentials.credentials != _api_key:
        raise HTTPException(status_code=401, detail="Unauthorized")


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


def create_app(db: Db, scheduler: SchedulerService) -> FastAPI:
    auth = None

    if _api_key:
        from app.auth import AuthManager

        auth = AuthManager(
            auth_file=Path("data/auth.json"),
            cookie_name="mailmanager_session",
        )
        if not auth.has_password():
            logger.warning("No password set. Run: python scripts/set_password.py")

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncGenerator[None, None]:
        scheduler.start()
        purge_task: Optional[asyncio.Task] = None
        if auth is not None:
            purge_task = asyncio.create_task(auth.purge_loop())
        yield
        await scheduler.stop()
        if purge_task is not None:
            purge_task.cancel()

    app = FastAPI(title="MailManager", lifespan=lifespan)
    app.state.db = db
    app.state.scheduler = scheduler

    # NiceGUI page handlers receive request.app = nicegui's core.app (not this app),
    # so we attach db/scheduler to nicegui's app.state for page handler access.
    from nicegui import app as nicegui_app

    nicegui_app.state.db = db
    nicegui_app.state.scheduler = scheduler

    auth_deps = [Depends(verify_token)]
    app.include_router(configs.router, prefix="/api", dependencies=auth_deps)
    app.include_router(rules.router, prefix="/api", dependencies=auth_deps)
    app.include_router(spam.router, prefix="/api", dependencies=auth_deps)
    app.include_router(scheduler_routes.router, prefix="/api", dependencies=auth_deps)
    app.include_router(logging_config.router, prefix="/api", dependencies=auth_deps)

    if auth is not None:

        @app.middleware("http")
        async def _auth_gate(request: Request, call_next: Callable) -> Response:
            path = request.url.path
            if path in _PUBLIC_PATHS or any(
                path.startswith(p) for p in _BYPASS_PREFIXES
            ):
                return await call_next(request)
            token = request.cookies.get(auth.cookie_name, "")
            if auth.verify_token(token):
                return await call_next(request)
            return RedirectResponse(url="/login", status_code=302)

        @app.get("/login")
        async def login_page() -> FileResponse:
            return FileResponse(_STATIC_ROOT / "login.html")

        @app.post("/auth/login")
        async def auth_login(request: Request, password: str = Form(...)) -> Response:
            ip = auth.client_ip(
                dict(request.headers),
                request.client.host if request.client else None,
            )
            success, _ = auth.attempt_login(password, ip)
            if not success:
                return RedirectResponse(url="/login?error=1", status_code=302)
            token = auth.create_token()
            redirect = RedirectResponse(url="/", status_code=302)
            auth.set_cookie(redirect, token, _is_secure(dict(request.headers)))
            return redirect

        @app.get("/auth/logout")
        async def auth_logout() -> Response:
            redirect = RedirectResponse(url="/login", status_code=302)
            auth.clear_cookie(redirect)
            return redirect

    # Register NiceGUI pages and mount into FastAPI app
    from nicegui import ui
    from .ui import pages  # noqa: F401 — triggers @ui.page registrations

    ui.run_with(app, storage_secret=_load_storage_secret(Path("data")))

    return app
