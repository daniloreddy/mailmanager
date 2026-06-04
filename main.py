import sys
import argparse
import portalocker
import logging
import os
from pathlib import Path

from mailmanager.db import Db
from mailmanager.processing import ProcessingService
from mailmanager.tui import MailManagerApp


def configure_logging():
    log_file = Path("data/mailmanager.log")
    log_file.parent.mkdir(parents=True, exist_ok=True)
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s - %(name)s - %(levelname)s - %(message)s",
        handlers=[
            logging.FileHandler(log_file),
            logging.StreamHandler(sys.stdout),
        ],
    )


def main():
    parser = argparse.ArgumentParser(description="MailManager in Python")
    parser.add_argument("-tui", action="store_true", help="Run the Textual UI")
    parser.add_argument("-imap", action="store_true", help="Run the Textual UI (alias)")
    parser.add_argument(
        "-rules", action="store_true", help="Run the Textual UI (alias)"
    )
    args = parser.parse_args()

    # Environment variables
    mode = os.environ.get("MAILMANAGER_MODE", "headless").lower()
    sa_host = os.environ.get("SPAMASSASSIN_HOST")

    # Single instance lock
    lock_file = Path("data/mailmanager.lock")
    lock_file.parent.mkdir(parents=True, exist_ok=True, mode=0o700)

    configure_logging()
    logger = logging.getLogger(__name__)

    try:
        with portalocker.Lock(lock_file, timeout=1):
            db = Db("data")

            # Auto-configure SpamAssassin host
            if sa_host:
                config = db.load_spam_config()
                if config.host != sa_host:
                    logger.info(f"Updating SpamAssassin host to {sa_host}")
                    config.host = sa_host
                    db.save_spam_config(config)

            if args.tui or args.imap or args.rules or mode == "tui":
                app = MailManagerApp(db=db)
                app.run()
                return

            # Headless processing
            spam_config = db.load_spam_config()
            imaps = db.load_imaps()
            rules = db.load_rules()

            if not imaps:
                logger.warning(
                    "No IMAP configurations found. Configure them via UI or DB."
                )
                return

            service = ProcessingService(db, rules, spam_config)

            for imap in imaps:
                service.process_account(imap)

    except portalocker.exceptions.LockException:
        logger.error("MailManager is already running.")
        sys.exit(1)


if __name__ == "__main__":
    main()
