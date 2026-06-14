from fastapi import Request

from ..db import Db
from ..scheduler import SchedulerService


def get_db(request: Request) -> Db:
    return request.app.state.db


def get_scheduler(request: Request) -> SchedulerService:
    return request.app.state.scheduler
