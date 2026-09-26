from __future__ import annotations

from typing import Optional, Union, Any
from sqlalchemy.orm import Session

from app.constant.optimization.job_status import OptimizationJobStatus
from app.dto.request.optimization_request import OptimizationJobRequest
from app.entity.optimization.optimization_job import OptimizationJob
from app.entity.trip_model import Trip
from app.exception.app_exception import AppException
from app.exception.error_code import ErrorCode
from app.repository.optimization.optimization_job_repository import OptimizationJobRepository


class OptimizationJobService:
    def __init__(self, repository: Optional[OptimizationJobRepository] = None):
        self.repository = repository or OptimizationJobRepository()

    def create_job(
        self,
        trip_id: Union[int, str],
        config: Union[OptimizationJobRequest, Any],
        db: Session,
    ) -> OptimizationJob:
        """
        Tạo optimization job mới cho trip.
        Kiểm tra trip có tồn tại và khởi tạo job với status PENDING.
        """
        trip = None
        try:
            numeric_trip_id = int(trip_id)
            trip = db.query(Trip).filter(Trip.id == numeric_trip_id).first()
        except (ValueError, TypeError):
            trip = db.query(Trip).filter(Trip.id == str(trip_id)).first()

        if not trip:
            raise AppException(ErrorCode.TRIP_NOT_FOUND)

        objective = getattr(config, "objective", None) or getattr(config, "algorithm_objective", "MAX_VOLUME")
        objective_val = objective.value if hasattr(objective, "value") else str(objective)
        job = OptimizationJob(
            trip_id=trip.id,
            algorithm_objective=objective_val,
            status=OptimizationJobStatus.PENDING.value,
        )
        return self.repository.create(job, db)

    def get_job(self, job_id: Union[int, str], db: Session) -> OptimizationJob:
        """
        Tìm optimization job theo numeric primary key (hoặc transitional job_uuid).
        Nếu không tìm thấy, raise AppException(ErrorCode.OPTIMIZATION_JOB_NOT_FOUND).
        """
        job = None
        try:
            numeric_id = int(job_id)
            job = self.repository.find_by_id(numeric_id, db)
        except (ValueError, TypeError):
            job = self.repository.find_by_job_uuid(job_id, db)

        if not job:
            raise AppException(ErrorCode.OPTIMIZATION_JOB_NOT_FOUND)
        return job

    def find_by_trip_id(self, trip_id: Union[int, str], db: Session) -> list[OptimizationJob]:
        """
        Tìm tất cả jobs theo trip_id.
        """
        try:
            numeric_trip_id = int(trip_id)
        except (ValueError, TypeError):
            return []
        return self.repository.find_by_trip_id(numeric_trip_id, db)

    def find_by_status(
        self,
        status: Union[OptimizationJobStatus, str],
        db: Session,
    ) -> list[OptimizationJob]:
        """
        Tìm tất cả jobs theo trạng thái.
        """
        return self.repository.find_by_status(status, db)

    def update_status(
        self,
        job_id: Optional[Union[int, str]] = None,
        status: Union[OptimizationJobStatus, str] = OptimizationJobStatus.PENDING,
        execution_time_ms: Optional[int] = None,
        db: Optional[Session] = None,
        job_uuid: Optional[str] = None,
        computation_ms: Optional[int] = None,
        **kwargs: Any,
    ) -> OptimizationJob:
        """
        Cập nhật trạng thái và thời gian tính toán của job.
        Status flow: PENDING -> RUNNING -> COMPLETED / FAILED / TIMEOUT / NO_SOLUTION / PARTIAL
        """
        target_id = job_id if job_id is not None else job_uuid
        if target_id is None:
            raise AppException(ErrorCode.OPTIMIZATION_JOB_NOT_FOUND)
        job = self.get_job(target_id, db)
        status_val = status.value if hasattr(status, "value") else str(status)
        job.status = status_val
        exec_time = execution_time_ms if execution_time_ms is not None else computation_ms
        if exec_time is not None:
            job.execution_time_ms = exec_time
        return self.repository.update(job, db)
