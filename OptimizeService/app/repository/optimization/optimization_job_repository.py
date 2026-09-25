from typing import Optional, Union
from sqlalchemy.orm import Session

from app.constant.optimization.job_status import OptimizationJobStatus
from app.entity.optimization.optimization_job import OptimizationJob


class OptimizationJobRepository:
    def create(self, job: OptimizationJob, db: Session) -> OptimizationJob:
        db.add(job)
        db.commit()
        db.refresh(job)
        return job

    def find_by_id(self, job_id: int, db: Session) -> Optional[OptimizationJob]:
        return db.query(OptimizationJob).filter(OptimizationJob.id == job_id).first()

    def find_by_trip_id(self, trip_id: int, db: Session) -> list[OptimizationJob]:
        return db.query(OptimizationJob).filter(OptimizationJob.trip_id == trip_id).all()

    def find_by_status(self, status: Union[OptimizationJobStatus, str], db: Session) -> list[OptimizationJob]:
        status_value = status.value if isinstance(status, OptimizationJobStatus) else str(status)
        return db.query(OptimizationJob).filter(OptimizationJob.status == status_value).all()

    def update(self, job: OptimizationJob, db: Session) -> OptimizationJob:
        db.commit()
        db.refresh(job)
        return job
