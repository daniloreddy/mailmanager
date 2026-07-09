from collections.abc import Generator
from pathlib import Path

import pytest
from _pytest.monkeypatch import MonkeyPatch
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.db import Db
from app.models import SchedulerConfig

_PASSWORD = "correct-horse-battery-staple"


@pytest.fixture(scope="module")
def data_dir(tmp_path_factory: pytest.TempPathFactory) -> Path:
    d = tmp_path_factory.mktemp("mailmanager_data")
    # Disabled + short interval purely to keep each TestClient(...) __exit__ fast
    # (SchedulerService.stop() itself returns promptly regardless — see
    # tests/test_scheduler.py — this is a speed choice, not a correctness workaround).
    Db(str(d)).save_scheduler_config(SchedulerConfig(enabled=False, interval_seconds=1))
    return d


@pytest.fixture(scope="module")
def app(data_dir: Path) -> Generator[FastAPI, None, None]:
    # app.main reads REQUIRE_AUTH/MAILMANAGER_DATA_DIR at import time, so
    # these must be set before the first import — never point this at the real
    # data/ directory (uvicorn.md §6).
    mp = MonkeyPatch()
    mp.setenv("MAILMANAGER_DATA_DIR", str(data_dir))
    mp.setenv("REQUIRE_AUTH", "true")

    import app.main as main_module

    assert main_module.auth is not None
    main_module.auth.set_password(_PASSWORD)

    yield main_module.app
    mp.undo()


def test_health_endpoint(app: FastAPI) -> None:
    with TestClient(app) as client:
        resp = client.get("/health")
        assert resp.status_code == 200
        assert resp.json() == {"status": "ok"}


def test_redirects_to_login_without_cookie(app: FastAPI) -> None:
    with TestClient(app) as client:
        resp = client.get("/ui", follow_redirects=False)
        assert resp.status_code == 302
        assert resp.headers["location"] == "/login"


def test_login_flow_success_and_failure(app: FastAPI) -> None:
    with TestClient(app) as client:
        bad = client.post("/auth/login", data={"password": "wrong"}, follow_redirects=False)
        assert bad.status_code == 302
        assert "error=1" in bad.headers["location"]
        assert "mailmanager_session" not in bad.cookies

        good = client.post("/auth/login", data={"password": _PASSWORD}, follow_redirects=False)
        assert good.status_code == 302
        assert good.headers["location"] == "/ui"
        assert "mailmanager_session" in good.cookies


def test_rate_limit_blocks_ip_after_repeated_failures(app: FastAPI) -> None:
    with TestClient(app) as client:
        for _ in range(5):
            client.post("/auth/login", data={"password": "wrong"}, follow_redirects=False)

        # 6th attempt is blocked even with the correct password.
        resp = client.post("/auth/login", data={"password": _PASSWORD}, follow_redirects=False)
        assert resp.status_code == 302
        assert "error=1" in resp.headers["location"]
