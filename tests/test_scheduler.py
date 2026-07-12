import asyncio
from pathlib import Path

import pytest

from app.config import config
from app.db import Db
from app.scheduler import SchedulerService


@pytest.mark.asyncio
async def test_stop_does_not_block_for_full_interval(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setitem(config._cache, "SCHEDULER_ENABLED", "false")
    monkeypatch.setitem(config._cache, "SCHEDULER_INTERVAL_SECONDS", "300")

    db = Db(str(tmp_path / "data"))
    scheduler = SchedulerService(db)
    scheduler.start()

    await asyncio.wait_for(scheduler.stop(), timeout=2)
