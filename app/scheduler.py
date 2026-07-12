import asyncio
import logging
import time
from dataclasses import dataclass

from .config import config
from .db import Db
from .metrics import MetricsRecord, metrics
from .models import SpamAssassinConfig
from .processing import ProcessingService

logger = logging.getLogger(__name__)


@dataclass
class SchedulerStatus:
    is_running: bool = False
    last_run_at: float | None = None
    next_run_at: float | None = None
    last_error: str | None = None
    run_count: int = 0


class SchedulerService:
    def __init__(self, db: Db) -> None:
        self._db = db
        self._stop_event = asyncio.Event()
        self._run_now_event = asyncio.Event()
        self._status = SchedulerStatus()
        self._task: asyncio.Task | None = None

    def start(self) -> None:
        self._task = asyncio.create_task(self._loop())

    async def stop(self) -> None:
        self._stop_event.set()
        self._run_now_event.set()  # wake _loop() if it's sleeping in wait_for
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
            if config.get_bool("SCHEDULER_ENABLED"):
                await self._do_run()

            interval_seconds = config.get_int("SCHEDULER_INTERVAL_SECONDS", 300)
            self._status.next_run_at = time.time() + interval_seconds

            try:
                await asyncio.wait_for(
                    self._run_now_event.wait(),
                    timeout=interval_seconds,
                )
                self._run_now_event.clear()
            except TimeoutError:
                pass

    async def _do_run(self) -> None:
        if self._status.is_running:
            return
        self._status.is_running = True
        self._status.last_run_at = time.time()
        self._status.last_error = None
        loop = asyncio.get_running_loop()
        try:
            records = await loop.run_in_executor(None, self._run_sync)
            for record in records:
                await metrics.record(record)
                if record.status == "error":
                    self._status.last_error = record.error_message
            await metrics.purge_old(days=30)
        except Exception as e:
            self._status.last_error = str(e)
            logger.error(f"Scheduler run failed: {e}")
        finally:
            self._status.is_running = False
            self._status.run_count += 1

    def _run_sync(self) -> list[MetricsRecord]:
        spam_config = SpamAssassinConfig(
            enabled=config.get_bool("SPAM_ENABLED"),
            host=config.get("SPAM_HOST", "127.0.0.1"),
            port=config.get_int("SPAM_PORT", 783),
            user=config.get("SPAM_USER") or None,
            connect_timeout_millis=config.get_int("SPAM_CONNECT_TIMEOUT_MS", 3000),
            read_timeout_millis=config.get_int("SPAM_READ_TIMEOUT_MS", 5000),
        )
        imaps = self._db.load_imaps()
        rules = self._db.load_rules()
        if not imaps:
            logger.info("No IMAP configurations found, skipping run.")
            return []

        service = ProcessingService(self._db, rules, spam_config)
        records: list[MetricsRecord] = []
        for imap in imaps:
            start = time.monotonic()
            error: str | None = None
            try:
                service.process_account(imap)
            except Exception as exc:
                # process_account already catches and logs IMAP/connection errors
                # internally (fail-open) — this only fires for a truly unexpected bug,
                # so most failed accounts still surface here as "ok" with no error
                # detail. Good enough for run-count/duration history without touching
                # processing.py's own error handling.
                error = str(exc)
                logger.error(f"Unexpected error processing account {imap.name}: {exc}")
            records.append(
                MetricsRecord(
                    timestamp=time.time(),
                    status="error" if error else "ok",
                    duration_s=time.monotonic() - start,
                    error_message=error,
                    extra={"account": imap.name},
                )
            )
        return records
