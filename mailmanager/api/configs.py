from typing import List

from fastapi import APIRouter, Depends, HTTPException, status

from ..db import Db
from ..models import ImapConfig
from .deps import get_db

router = APIRouter(tags=["configs"])


@router.get("/configs", response_model=List[ImapConfig])
def list_configs(db: Db = Depends(get_db)) -> List[ImapConfig]:
    return db.load_imaps()


@router.post("/configs", status_code=status.HTTP_201_CREATED, response_model=ImapConfig)
def create_config(config: ImapConfig, db: Db = Depends(get_db)) -> ImapConfig:
    db.save_imaps([config])
    return config


@router.put("/configs/{name}", response_model=ImapConfig)
def update_config(
    name: str, config: ImapConfig, db: Db = Depends(get_db)
) -> ImapConfig:
    existing = {c.name: c for c in db.load_imaps()}
    if name not in existing:
        raise HTTPException(status_code=404, detail="Config not found")
    config.name = name
    db.save_imaps([config])
    return config


@router.delete("/configs/{name}", status_code=status.HTTP_204_NO_CONTENT)
def delete_config(name: str, db: Db = Depends(get_db)) -> None:
    existing = {c.name for c in db.load_imaps()}
    if name not in existing:
        raise HTTPException(status_code=404, detail="Config not found")
    db.delete_imap(name)
