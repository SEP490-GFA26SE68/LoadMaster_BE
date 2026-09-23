from typing import Optional, List
from sqlalchemy.orm import Session

from app.entity.optimization.load_plan import LoadPlan


class LoadPlanRepository:
    def create(self, plan: LoadPlan, db: Session) -> LoadPlan:
        db.add(plan)
        db.flush()
        return plan

    def find_by_id(self, plan_id: int, db: Session) -> Optional[LoadPlan]:
        return db.query(LoadPlan).filter(LoadPlan.id == plan_id).first()

    def find_by_job_id(self, job_id: int, db: Session) -> List[LoadPlan]:
        return db.query(LoadPlan).filter(LoadPlan.job_id == job_id).all()

    def update(self, plan: LoadPlan, db: Session) -> LoadPlan:
        db.flush()
        return plan
