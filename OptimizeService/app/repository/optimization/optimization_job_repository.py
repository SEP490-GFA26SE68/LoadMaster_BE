from typing import Optional, Union
from sqlalchemy.orm import Session

from app.constant.optimization.job_status import OptimizationJobStatus
from app.entity.optimization.optimization_job import OptimizationJob


class OptimizationJobRepository:
    def create(self, job: OptimizationJob, db: Session) -> OptimizationJob:
        db.add(job)
        db.commit()
        db.refresh(job)
        if getattr(job, "_job_uuid", None) and job.id is not None:
            OptimizationJob._uuid_to_id_map[str(job._job_uuid)] = job.id
        return job

    def find_by_id(self, job_id: Union[int, str], db: Session) -> Optional[OptimizationJob]:
        try:
            numeric_id = int(job_id)
        except (ValueError, TypeError):
            return None
        return db.query(OptimizationJob).filter(OptimizationJob.id == numeric_id).first()

    def find_by_job_uuid(self, job_uuid: Union[str, int], db: Session) -> Optional[OptimizationJob]:
        try:
            numeric_id = int(job_uuid)
            return self.find_by_id(numeric_id, db)
        except (ValueError, TypeError):
            str_uuid = str(job_uuid)
            mapped_id = OptimizationJob._uuid_to_id_map.get(str_uuid)
            if mapped_id is not None:
                return self.find_by_id(mapped_id, db)
            # Check objects in session identity map
            try:
                for obj in db.identity_map.values():
                    if isinstance(obj, OptimizationJob) and getattr(obj, "_job_uuid", None) == str_uuid:
                        if obj.id is not None:
                            OptimizationJob._uuid_to_id_map[str_uuid] = obj.id
                        return obj
            except Exception:
                pass
            return None

    def find_by_trip_id(self, trip_id: Union[int, str], db: Session) -> list[OptimizationJob]:
        try:
            numeric_id = int(trip_id)
        except (ValueError, TypeError):
            return []
        return db.query(OptimizationJob).filter(OptimizationJob.trip_id == numeric_id).all()

    def find_by_status(self, status: Union[OptimizationJobStatus, str], db: Session) -> list[OptimizationJob]:
        status_value = status.value if isinstance(status, OptimizationJobStatus) else str(status)
        return db.query(OptimizationJob).filter(OptimizationJob.status == status_value).all()

    def update(self, job: OptimizationJob, db: Session) -> OptimizationJob:
        db.commit()
        db.refresh(job)
        return job
