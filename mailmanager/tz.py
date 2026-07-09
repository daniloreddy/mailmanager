import logging
import os
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

logger = logging.getLogger(__name__)


def get_timezone() -> ZoneInfo:
    tz_name = os.environ.get("TZ", "UTC")
    try:
        return ZoneInfo(tz_name)
    except ZoneInfoNotFoundError:
        logger.warning("Unknown TZ=%s, falling back to UTC", tz_name)
        return ZoneInfo("UTC")
