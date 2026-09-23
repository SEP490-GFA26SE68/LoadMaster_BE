from __future__ import annotations

import uuid
from typing import Optional, Union
from uuid import UUID
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
        trip_id: Union[UUID, str],
        config: OptimizationJobRequest,
        db: Session,
    ) -> OptimizationJob:
        """
        Tạo optimization job mới cho trip.
        Kiểm tra trip có tồn tại, khởi tạo job với status PENDING và UUID ngẫu nhiên.
        """
        trip_str = str(trip_id)
        trip = db.query(Trip).filter(Trip.id == trip_str).first()
        if not trip:
            raise AppException(ErrorCode.TRIP_NOT_FOUND)

        objective_val = config.objective.value if hasattr(config.objective, "value") else str(config.objective)
        job = OptimizationJob(
            trip_id=trip_str,
            job_uuid=str(uuid.uuid4()),
            objective=objective_val,
            time_limit_sec=config.time_limit_sec,
            status=OptimizationJobStatus.PENDING.value,
            algorithm_name=config.algorithm_name,
        )
        return self.repository.create(job, db)

    def get_job(self, job_uuid: str, db: Session) -> OptimizationJob:
        """
        Tìm optimization job theo job_uuid.
        Nếu không tìm thấy, raise AppException(ErrorCode.OPTIMIZATION_JOB_NOT_FOUND).
        """
        job = self.repository.find_by_job_uuid(str(job_uuid), db)
        if not job:
            raise AppException(ErrorCode.OPTIMIZATION_JOB_NOT_FOUND)
        return job

    def find_by_trip_id(self, trip_id: Union[UUID, str], db: Session) -> list[OptimizationJob]:
        """
        Tìm tất cả jobs theo trip_id.
        """
        return self.repository.find_by_trip_id(str(trip_id), db)

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
        job_uuid: str,
        status: Union[OptimizationJobStatus, str],
        computation_ms: Optional[int] = None,
        db: Session = None,
    ) -> OptimizationJob:
        """
        Cập nhật trạng thái và thời gian tính toán của job.
        Status flow: PENDING -> RUNNING -> COMPLETED / FAILED / TIMEOUT / NO_SOLUTION / PARTIAL
        """
        job = self.get_job(job_uuid, db)
        status_val = status.value if hasattr(status, "value") else str(status)
        job.status = status_val
        if computation_ms is not None:
            job.computation_ms = computation_ms
        return self.repository.update(job, db)
