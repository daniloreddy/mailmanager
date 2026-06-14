from typing import Any, Dict

from fastapi import APIRouter, Depends, HTTPException, status

from ..db import Db
from ..models import SchedulerConfig
from ..scheduler import SchedulerService
from .deps import get_db, get_scheduler

router = APIRouter(tags=["scheduler"])


@router.get("/scheduler/status")
def get_status(scheduler: SchedulerService = Depends(get_scheduler)) -> Dict[str, Any]:
    s = scheduler.get_status()
    return {
        "is_running": s.is_running,
        "last_run_at": s.last_run_at,
        "next_run_at": s.next_run_at,
        "last_error": s.last_error,
        "run_count": s.run_count,
    }


@router.post("/scheduler/run", status_code=status.HTTP_202_ACCEPTED)
def run_now(scheduler: SchedulerService = Depends(get_scheduler)) -> Dict[str, bool]:
    if not scheduler.trigger_run_now():
        raise HTTPException(status_code=409, detail="Already running")
    return {"queued": True}


@router.get("/scheduler/config", response_model=SchedulerConfig)
def get_scheduler_config(db: Db = Depends(get_db)) -> SchedulerConfig:
    return db.load_scheduler_config()


@router.put("/scheduler/config", status_code=status.HTTP_204_NO_CONTENT)
def update_scheduler_config(config: SchedulerConfig, db: Db = Depends(get_db)) -> None:
    db.save_scheduler_config(config)
