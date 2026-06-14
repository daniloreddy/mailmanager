import logging
import os
import sys
from logging.handlers import RotatingFileHandler
from pathlib import Path

import portalocker
import uvicorn

from mailmanager.db import Db
from mailmanager.models import LoggingConfig
from mailmanager.scheduler import SchedulerService
from mailmanager.server import create_app


def configure_logging(cfg: LoggingConfig) -> None:
    log_dir = Path("logs")
    log_dir.mkdir(parents=True, exist_ok=True)

    root = logging.getLogger()
    root.setLevel(cfg.level.value)
    root.handlers.clear()

    formatter = logging.Formatter(
        "%(asctime)s - %(name)s - %(levelname)s - %(message)s"
    )

    file_handler = RotatingFileHandler(
        log_dir / "mailmanager.log",
        maxBytes=cfg.maxBytes,
        backupCount=cfg.backupCount,
    )
    file_handler.setFormatter(formatter)

    stream_handler = logging.StreamHandler(sys.stdout)
    stream_handler.setFormatter(formatter)

    root.addHandler(file_handler)
    root.addHandler(stream_handler)


def main() -> None:
    logging.basicConfig(level=logging.INFO)
    logger = logging.getLogger(__name__)

    api_key = os.environ.get("MAILMANAGER_API_KEY")
    host = "0.0.0.0" if api_key else "127.0.0.1"
    port = int(os.environ.get("MAILMANAGER_PORT", "8080"))

    lock_file = Path("data/mailmanager.lock")
    lock_file.parent.mkdir(parents=True, exist_ok=True, mode=0o700)

    try:
        with portalocker.Lock(lock_file, timeout=1):
            db = Db("data")
            configure_logging(db.load_logging_config())
            logger = logging.getLogger(__name__)

            scheduler = SchedulerService(db)
            app = create_app(db, scheduler)

            logger.info(f"Starting MailManager on {host}:{port}")
            uvicorn.run(app, host=host, port=port, workers=1)

    except portalocker.exceptions.LockException:
        logger.error("MailManager is already running.")
        sys.exit(1)


if __name__ == "__main__":
    main()
