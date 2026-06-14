from typing import List

from fastapi import APIRouter, Depends, HTTPException, status

from ..db import Db
from ..models import ActionType, ConditionOperator, Rule
from .deps import get_db

router = APIRouter(tags=["rules"])

_OPERATORS_REQUIRING_VALUE = {
    ConditionOperator.CONTAINS,
    ConditionOperator.NOT_CONTAINS,
    ConditionOperator.STARTS_WITH,
    ConditionOperator.ENDS_WITH,
    ConditionOperator.REGEX,
}
_ACTIONS_REQUIRING_DEST = {
    ActionType.MOVE,
    ActionType.COPY,
    ActionType.FORWARD,
    ActionType.ADD_LABEL,
    ActionType.REMOVE_LABEL,
}


def _validate_rule(rule: Rule) -> None:
    if rule.conditionOperator in _OPERATORS_REQUIRING_VALUE and not rule.conditionValue:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"conditionValue cannot be empty for operator {rule.conditionOperator}",
        )
    if rule.actionType in _ACTIONS_REQUIRING_DEST and not rule.destValue:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"destValue cannot be empty for action {rule.actionType}",
        )


@router.get("/rules", response_model=List[Rule])
def list_rules(db: Db = Depends(get_db)) -> List[Rule]:
    return db.load_rules()


@router.post("/rules", status_code=status.HTTP_201_CREATED, response_model=Rule)
def create_rule(rule: Rule, db: Db = Depends(get_db)) -> Rule:
    rule.id = None
    _validate_rule(rule)
    return db.save_rule(rule)


@router.put("/rules/{rule_id}", response_model=Rule)
def update_rule(rule_id: int, rule: Rule, db: Db = Depends(get_db)) -> Rule:
    existing = {r.id for r in db.load_rules()}
    if rule_id not in existing:
        raise HTTPException(status_code=404, detail="Rule not found")
    rule.id = rule_id
    _validate_rule(rule)
    return db.save_rule(rule)


@router.delete("/rules/{rule_id}", status_code=status.HTTP_204_NO_CONTENT)
def delete_rule(rule_id: int, db: Db = Depends(get_db)) -> None:
    existing = {r.id for r in db.load_rules()}
    if rule_id not in existing:
        raise HTTPException(status_code=404, detail="Rule not found")
    db.delete_rule(rule_id)
