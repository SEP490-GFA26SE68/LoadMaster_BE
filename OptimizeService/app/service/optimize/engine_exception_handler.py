from __future__ import annotations

import logging
from typing import Optional, Union, Any
from uuid import UUID
import httpx
from sqlalchemy.orm import Session

from app.constant.optimization.job_status import OptimizationJobStatus
from app.dto.optimization.engine.engine_response import EngineOptimizationResponse
from app.exception.app_exception import ServiceAuthenticationException
from app.service.optimize.optimization_job_service import OptimizationJobService

logger = logging.getLogger(__name__)


class EngineExceptionHandler:
    """
    Handler xử lý tất cả ngoại lệ phát sinh từ Optimization Engine (S3-07).
    Bao gồm:
      - Connection refused / network error -> FAILED, log SERVICE_UNAVAILABLE
      - Timeout (httpx.TimeoutException) -> TIMEOUT, log time limit exceeded
      - Engine trả 401 -> FAILED, log AUTH_ERROR
      - Engine trả 403 -> FAILED, log AUTH_FORBIDDEN
      - Keycloak auth error -> FAILED, log AUTH_ERROR
      - Kết quả 0 placements -> NO_SOLUTION
      - Kết quả một số package chưa xếp được -> PARTIAL
      - Kết quả xếp thành công tất cả -> COMPLETED
      - Không bao giờ crash server/caller khi có ngoại lệ
    """

    def __init__(
        self,
        job_service: Optional[OptimizationJobService] = None,
        notification_service: Optional[Any] = None,
    ):
        self.job_service = job_service or OptimizationJobService()
        if notification_service is not None:
            self.notification_service = notification_service
        else:
            try:
                from app.service.optimize.job_notification_service import get_job_notification_service
                self.notification_service = get_job_notification_service()
            except ImportError:
                self.notification_service = None

    def handle(
        self,
        exc: Exception,
        job_uuid: Union[str, UUID],
        db: Session,
        computation_ms: Optional[int] = None,
    ) -> OptimizationJobStatus:
        """
        Xử lý ngoại lệ, cập nhật trạng thái job và ghi log tương ứng.
        Trả về OptimizationJobStatus đã cập nhật.
        """
        job_uuid_str = str(job_uuid)
        status = OptimizationJobStatus.FAILED

        if isinstance(exc, httpx.TimeoutException):
            status = OptimizationJobStatus.TIMEOUT
            log_message = f"TIMEOUT: Engine request timed out for job {job_uuid_str}: {exc}. Time limit exceeded."
            logger.warning(log_message)

        elif isinstance(exc, httpx.HTTPStatusError):
            code = exc.response.status_code if exc.response is not None else 500
            if code == 401:
                status = OptimizationJobStatus.FAILED
                log_message = f"AUTH_ERROR: Optimization engine returned 401 Unauthorized for job {job_uuid_str}: {exc}"
                logger.error(log_message)
            elif code == 403:
                status = OptimizationJobStatus.FAILED
                log_message = f"AUTH_FORBIDDEN: Optimization engine returned 403 Forbidden for job {job_uuid_str}: {exc}"
                logger.error(log_message)
            else:
                status = OptimizationJobStatus.FAILED
                log_message = f"ENGINE_HTTP_ERROR: Status {code} for job {job_uuid_str}: {exc}"
                logger.error(log_message)

        elif isinstance(exc, (httpx.ConnectError, httpx.NetworkError, httpx.RequestError)):
            status = OptimizationJobStatus.FAILED
            log_message = f"SERVICE_UNAVAILABLE: Cannot connect to Optimization engine for job {job_uuid_str}: {exc}"
            logger.error(log_message)

        elif isinstance(exc, ServiceAuthenticationException):
            status = OptimizationJobStatus.FAILED
            log_message = f"AUTH_ERROR: Service authentication failed for job {job_uuid_str}: {exc}"
            logger.error(log_message)

        else:
            status = OptimizationJobStatus.FAILED
            log_message = f"UNEXPECTED_ERROR: Optimization job {job_uuid_str} encountered unexpected error: {exc}"
            logger.exception(log_message)

        # Cập nhật status trong database
        try:
            self.job_service.update_status(
                job_uuid=job_uuid_str,
                status=status,
                computation_ms=computation_ms,
                db=db,
            )
        except Exception as db_exc:
            logger.error(f"Failed to update job status in DB for {job_uuid_str}: {db_exc}")

        # Gửi thông báo WebSocket nếu notification_service có sẵn
        self._notify_status(job_uuid_str, status, computation_ms=computation_ms)

        return status

    def classify_result(self, result: EngineOptimizationResponse) -> OptimizationJobStatus:
        """
        Phân loại kết quả từ Engine:
          - 0 placements -> NO_SOLUTION
          - len(unplaced) > 0 -> PARTIAL
          - Ngược lại -> COMPLETED
        """
        if not result.placements or len(result.placements) == 0:
            logger.warning("Optimization result: 0 placements -> NO_SOLUTION")
            return OptimizationJobStatus.NO_SOLUTION

        if result.unplaced and len(result.unplaced) > 0:
            logger.info(f"Optimization result: {len(result.unplaced)} packages unplaced -> PARTIAL")
            return OptimizationJobStatus.PARTIAL

        logger.info(f"Optimization result: All {len(result.placements)} packages placed -> COMPLETED")
        return OptimizationJobStatus.COMPLETED

    def handle_result(
        self,
        result: EngineOptimizationResponse,
        job_uuid: Union[str, UUID],
        db: Session,
        computation_ms: Optional[int] = None,
    ) -> OptimizationJobStatus:
        """
        Xử lý kết quả trả về từ Engine, cập nhật trạng thái job tương ứng.
        """
        job_uuid_str = str(job_uuid)
        status = self.classify_result(result)

        comp_ms = computation_ms
        if comp_ms is None and result.metrics is not None and result.metrics.computation_ms is not None:
            comp_ms = result.metrics.computation_ms

        try:
            self.job_service.update_status(
                job_uuid=job_uuid_str,
                status=status,
                computation_ms=comp_ms,
                db=db,
            )
        except Exception as db_exc:
            logger.error(f"Failed to update job status in DB for {job_uuid_str}: {db_exc}")

        self._notify_status(job_uuid_str, status, computation_ms=comp_ms)
        return status

    def _notify_status(
        self,
        job_uuid: str,
        status: OptimizationJobStatus,
        computation_ms: Optional[int] = None,
    ) -> None:
        if self.notification_service is not None and hasattr(self.notification_service, "notify_status"):
            try:
                self.notification_service.notify_status(
                    job_uuid,
                    status,
                    computation_ms=computation_ms,
                )
            except Exception as notify_exc:
                logger.warning(f"Failed to send status notification for {job_uuid}: {notify_exc}")
