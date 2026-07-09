import argparse
import os
import sys
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
# (mailmanager.server / app.auth read REQUIRE_AUTH / TRUSTED_PROXIES at import time).
load_dotenv(_resolve_env_path())

import logging  # noqa: E402
from logging.handlers import RotatingFileHandler  # noqa: E402

import portalocker  # noqa: E402
import uvicorn  # noqa: E402

from mailmanager.db import Db  # noqa: E402
from mailmanager.models import LoggingConfig  # noqa: E402
from mailmanager.scheduler import SchedulerService  # noqa: E402
from mailmanager.server import create_app  # noqa: E402

# python:3.12-slim (our base image) always creates this file — reliable Docker signal.
_IS_DOCKER = Path("/.dockerenv").exists()


def configure_logging(cfg: LoggingConfig) -> None:
    root = logging.getLogger()
    root.setLevel(cfg.level.value)
    root.handlers.clear()

    formatter = logging.Formatter(
        "%(asctime)s - %(name)s - %(levelname)s - %(message)s"
    )

    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(formatter)
    root.addHandler(handler)

    if not _IS_DOCKER:
        # Docker logs are captured via `docker compose logs`; local runs get a
        # rotating file too so a closed terminal doesn't lose the daemon's history.
        log_dir = Path("data")
        log_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
        file_handler = RotatingFileHandler(
            log_dir / "mailmanager.log",
            maxBytes=5 * 1024 * 1024,
            backupCount=3,
            encoding="utf-8",
        )
        file_handler.setFormatter(formatter)
        root.addHandler(file_handler)


def main() -> None:
    logging.basicConfig(level=logging.INFO)
    logger = logging.getLogger(__name__)

    host = os.environ.get("HOST", "127.0.0.1")
    port = int(os.environ.get("PORT", "8080"))

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
