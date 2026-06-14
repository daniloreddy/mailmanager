from fastapi import APIRouter, Depends, status

from ..db import Db
from ..models import SpamAssassinConfig
from .deps import get_db

router = APIRouter(tags=["spam"])


@router.get("/spam", response_model=SpamAssassinConfig)
def get_spam_config(db: Db = Depends(get_db)) -> SpamAssassinConfig:
    return db.load_spam_config()


@router.put("/spam", status_code=status.HTTP_204_NO_CONTENT)
def update_spam_config(config: SpamAssassinConfig, db: Db = Depends(get_db)) -> None:
    db.save_spam_config(config)
