import asyncio
import logging
import time
from dataclasses import dataclass
from typing import Optional

from .db import Db
from .processing import ProcessingService

logger = logging.getLogger(__name__)


@dataclass
class SchedulerStatus:
    is_running: bool = False
    last_run_at: Optional[float] = None
    next_run_at: Optional[float] = None
    last_error: Optional[str] = None
    run_count: int = 0


class SchedulerService:
    def __init__(self, db: Db) -> None:
        self._db = db
        self._stop_event = asyncio.Event()
        self._run_now_event = asyncio.Event()
        self._status = SchedulerStatus()
        self._task: Optional[asyncio.Task] = None

    def start(self) -> None:
        self._task = asyncio.create_task(self._loop())

    async def stop(self) -> None:
        self._stop_event.set()
        if self._task:
            await self._task

    def trigger_run_now(self) -> bool:
        if self._status.is_running:
            return False
        self._run_now_event.set()
        return True

    def get_status(self) -> SchedulerStatus:
        return self._status

    async def _loop(self) -> None:
        while not self._stop_event.is_set():
            cfg = self._db.load_scheduler_config()
            if cfg.enabled:
                await self._do_run()

            self._status.next_run_at = time.time() + cfg.intervalSeconds

            try:
                await asyncio.wait_for(
                    self._run_now_event.wait(),
                    timeout=cfg.intervalSeconds,
                )
                self._run_now_event.clear()
            except asyncio.TimeoutError:
                pass

    async def _do_run(self) -> None:
        if self._status.is_running:
            return
        self._status.is_running = True
        self._status.last_run_at = time.time()
        self._status.last_error = None
        loop = asyncio.get_running_loop()
        try:
            await loop.run_in_executor(None, self._run_sync)
        except Exception as e:
            self._status.last_error = str(e)
            logger.error(f"Scheduler run failed: {e}")
        finally:
            self._status.is_running = False
            self._status.run_count += 1

    def _run_sync(self) -> None:
        spam_config = self._db.load_spam_config()
        imaps = self._db.load_imaps()
        rules = self._db.load_rules()
        if not imaps:
            logger.info("No IMAP configurations found, skipping run.")
            return
        service = ProcessingService(self._db, rules, spam_config)
        for imap in imaps:
            service.process_account(imap)
