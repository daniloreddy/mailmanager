import logging

from fastapi import APIRouter, Depends, status

from ..db import Db
from ..models import LoggingConfig
from .deps import get_db

router = APIRouter(tags=["logging"])


@router.get("/logging/config", response_model=LoggingConfig)
def get_logging_config(db: Db = Depends(get_db)) -> LoggingConfig:
    return db.load_logging_config()


@router.put("/logging/config", status_code=status.HTTP_204_NO_CONTENT)
def update_logging_config(config: LoggingConfig, db: Db = Depends(get_db)) -> None:
    db.save_logging_config(config)
    logging.getLogger().setLevel(config.level.value)
