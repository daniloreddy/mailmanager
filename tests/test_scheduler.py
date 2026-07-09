import asyncio
from pathlib import Path

import pytest

from app.db import Db
from app.models import SchedulerConfig
from app.scheduler import SchedulerService


@pytest.mark.asyncio
async def test_stop_does_not_block_for_full_interval(tmp_path: Path) -> None:
    db = Db(str(tmp_path / "data"))
    db.save_scheduler_config(SchedulerConfig(enabled=False, interval_seconds=300))
    scheduler = SchedulerService(db)
    scheduler.start()

    await asyncio.wait_for(scheduler.stop(), timeout=2)
