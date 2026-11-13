from __future__ import annotations

import uuid
from typing import Optional, Union
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
            trip_id: int,
            config: OptimizationJobRequest,
            db: Session,
    ) -> OptimizationJob:
        """
        Tạo Optimization Job cho Trip có ID kiểu BIGINT.
        """
        if isinstance(trip_id, bool) or not isinstance(trip_id, int) or trip_id <= 0:
            raise AppException(ErrorCode.INVALID_INPUT)

        trip = db.query(Trip).filter(Trip.id == trip_id).first()

        if not trip:
            raise AppException(ErrorCode.TRIP_NOT_FOUND)

        from app.service.optimize.algorithm_tier_service import AlgorithmTierService

        objective_val = (
            config.objective.value
            if hasattr(config.objective, "value")
            else str(config.objective)
        )

        tier = AlgorithmTierService.get_algorithm_tier(
            config.subscription_tier
        )

        resolved_algo = AlgorithmTierService.resolve_algorithm(
            tier,
            config.algorithm_name,
        )

        job = OptimizationJob(
            trip_id=trip_id,
            job_uuid=str(uuid.uuid4()),
            objective=objective_val,
            time_limit_sec=config.time_limit_sec,
            status=OptimizationJobStatus.PENDING.value,
            algorithm_name=resolved_algo,
            company_id=config.company_id,
            subscription_tier=tier,
            algorithm_tier=tier,
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

    def find_by_trip_id(self, trip_id: int, db: Session) -> list[OptimizationJob]:
        return self.repository.find_by_trip_id(trip_id, db)

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
        Cập nhật trạng thái và thời gian tính toán của job, rồi commit cùng mọi thứ đã ghi trong giao dịch.
        Status flow: PENDING -> RUNNING -> COMPLETED / FAILED / TIMEOUT / NO_SOLUTION / PARTIAL

        Trạng thái cuối là cuối: chỉ đổi khi job còn PENDING / RUNNING (UPDATE có điều kiện). Job đã kết thúc —
        thường là LoadMasterService đã đánh dấu FAILED JOB_INTERRUPTED và hoàn credit vì job quá hạn — thì
        rollback cả giao dịch (bỏ phương án vừa ghi của kết quả đến muộn) và ném JobAlreadyFinishedError.
        """
        status_val = status.value if hasattr(status, "value") else str(status)
        job = self.get_job(job_uuid, db)
        # Quyết định nằm ở câu UPDATE có điều kiện, không ở lần đọc trên: lượt ghi cùng lúc của Java không lọt qua.
        if not self.repository.transition_unfinished(str(job_uuid), status_val, computation_ms, db):
            db.rollback()
            raise JobAlreadyFinishedError(str(job_uuid), job.status, status_val)
        db.commit()
        # Sau commit job đã hết hạn trong phiên: đọc thuộc tính là đọc lại từ DB. Không đọc ngay ở đây để không mở
        # giao dịch mới giữ kết nối suốt lúc bộ giải chạy.
        return job


class JobAlreadyFinishedError(Exception):
    """Job đã ở trạng thái cuối khi một kết quả / trạng thái mới đến: phần đến muộn bị bỏ, không ghi gì."""

    def __init__(self, job_uuid: str, current_status: Optional[str], attempted_status: str):
        self.job_uuid = job_uuid
        self.current_status = current_status
        self.attempted_status = attempted_status
        super().__init__(
            f"Optimization job {job_uuid} already {current_status}; ignored transition to {attempted_status}"
        )
