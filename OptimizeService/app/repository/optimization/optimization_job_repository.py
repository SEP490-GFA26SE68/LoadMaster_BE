from typing import Optional, Union
from sqlalchemy import update
from sqlalchemy.orm import Session

from app.constant.optimization.job_status import OptimizationJobStatus, UNFINISHED_JOB_STATUSES
from app.entity.optimization.optimization_job import OptimizationJob


class OptimizationJobRepository:
    def create(self, job: OptimizationJob, db: Session) -> OptimizationJob:
        db.add(job)
        db.commit()
        db.refresh(job)
        return job

    def find_by_id(self, job_id: int, db: Session) -> Optional[OptimizationJob]:
        return db.query(OptimizationJob).filter(OptimizationJob.id == job_id).first()

    def find_by_job_uuid(self, job_uuid: str, db: Session) -> Optional[OptimizationJob]:
        return db.query(OptimizationJob).filter(OptimizationJob.job_uuid == str(job_uuid)).first()


    def find_by_trip_id(
            self,
            trip_id: int,
            db: Session,
    ) -> list[OptimizationJob]:
        return (
            db.query(OptimizationJob)
            .filter(OptimizationJob.trip_id == trip_id)
            .all()
        )


    def find_by_status(self, status: Union[OptimizationJobStatus, str], db: Session) -> list[OptimizationJob]:
        status_value = status.value if isinstance(status, OptimizationJobStatus) else str(status)
        return db.query(OptimizationJob).filter(OptimizationJob.status == status_value).all()

    def update(self, job: OptimizationJob, db: Session) -> OptimizationJob:
        db.commit()
        db.refresh(job)
        return job

    def transition_unfinished(
        self,
        job_uuid: str,
        status: str,
        computation_ms: Optional[int],
        db: Session,
    ) -> bool:
        """
        Đổi trạng thái job chỉ khi job còn PENDING / RUNNING, bằng một câu UPDATE có điều kiện (không đọc rồi ghi).
        Không commit: nơi gọi commit cùng phần đã ghi trong giao dịch (phương án của kết quả) hoặc rollback cả hai.
        Trả True khi đổi được đúng một dòng; False khi job đã ở trạng thái cuối (hoặc không có job).
        """
        values = {"status": status}
        if computation_ms is not None:
            values["computation_ms"] = computation_ms
        result = db.execute(
            update(OptimizationJob)
            .where(
                OptimizationJob.job_uuid == str(job_uuid),
                OptimizationJob.status.in_(UNFINISHED_JOB_STATUSES),
            )
            .values(**values)
            .execution_options(synchronize_session=False)
        )
        return result.rowcount == 1
