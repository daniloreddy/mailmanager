import os
from contextlib import asynccontextmanager
from pathlib import Path
from typing import AsyncGenerator

from fastapi import Depends, FastAPI, HTTPException
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from fastapi.staticfiles import StaticFiles

from .db import Db
from .scheduler import SchedulerService
from .api import configs, logging_config, rules, scheduler as scheduler_routes, spam

_api_key: str | None = os.environ.get("MAILMANAGER_API_KEY")
_bearer = HTTPBearer(auto_error=False)


def verify_token(
    credentials: HTTPAuthorizationCredentials | None = Depends(_bearer),
) -> None:
    if _api_key is None:
        return
    if credentials is None or credentials.credentials != _api_key:
        raise HTTPException(status_code=401, detail="Unauthorized")


def create_app(db: Db, scheduler: SchedulerService) -> FastAPI:
    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncGenerator[None, None]:
        scheduler.start()
        yield
        await scheduler.stop()

    app = FastAPI(title="MailManager", lifespan=lifespan)
    app.state.db = db
    app.state.scheduler = scheduler

    auth = [Depends(verify_token)]
    app.include_router(configs.router, prefix="/api", dependencies=auth)
    app.include_router(rules.router, prefix="/api", dependencies=auth)
    app.include_router(spam.router, prefix="/api", dependencies=auth)
    app.include_router(scheduler_routes.router, prefix="/api", dependencies=auth)
    app.include_router(logging_config.router, prefix="/api", dependencies=auth)

    web_dir = Path(__file__).parent.parent / "web"
    if web_dir.exists():
        app.mount("/", StaticFiles(directory=str(web_dir), html=True), name="static")

    return app
