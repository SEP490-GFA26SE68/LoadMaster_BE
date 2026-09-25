"""
Unit & Integration tests for EngineExceptionHandler — S3-07

Seams under test:
  - EngineExceptionHandler.handle(exc, job_uuid, db, computation_ms) -> OptimizationJobStatus
  - EngineExceptionHandler.classify_result(result) -> OptimizationJobStatus
  - EngineExceptionHandler.handle_result(result, job_uuid, db, computation_ms) -> OptimizationJobStatus

Acceptance Criteria:
  - Connection refused / network error -> update job FAILED, log SERVICE_UNAVAILABLE
  - Timeout (httpx.TimeoutException) -> update job TIMEOUT, log time limit exceeded
  - 0 placements -> update job NO_SOLUTION, record unplaced
  - Partial (some unplaced) -> update job PARTIAL, record unplaced
  - All placed -> update job COMPLETED
  - Engine returns 401 -> update job FAILED, log AUTH_ERROR
  - Engine returns 403 -> update job FAILED, log AUTH_FORBIDDEN
  - Engine auth error (ServiceAuthenticationException) -> update job FAILED
  - All exceptions handled gracefully, does not crash server
"""
import uuid
import pytest
import httpx
from unittest.mock import MagicMock
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from app.entity.trip_model import Base, Trip
from app.entity.optimization.optimization_job import OptimizationJob
from app.constant.optimization.job_status import OptimizationJobStatus
from app.exception.app_exception import ServiceAuthenticationException
from app.dto.optimization.engine.engine_response import (
    EngineOptimizationResponse,
    PlacementData,
    UnplacedData,
    MetricsData,
)
from app.service.optimize.engine_exception_handler import EngineExceptionHandler


# ---------------------------------------------------------------------------
# In-memory SQLite DB Fixtures
# ---------------------------------------------------------------------------

@pytest.fixture
def db_session():
    engine = create_engine("sqlite:///:memory:", echo=False)
    Base.metadata.create_all(engine)
    Session = sessionmaker(bind=engine)
    session = Session()

    # Tạo trip mẫu
    trip = Trip(id=str(uuid.uuid4()), status="DRAFT")
    session.add(trip)
    session.commit()

    yield session
    session.close()


@pytest.fixture
def sample_job(db_session):
    trip = db_session.query(Trip).first()
    job = OptimizationJob(
        job_uuid=str(uuid.uuid4()),
        trip_id=trip.id,
        objective="MINIMIZE_VEHICLE",
        time_limit_sec=30,
        status=OptimizationJobStatus.RUNNING.value,
        algorithm_name="DEFAULT_GREEDY",
    )
    db_session.add(job)
    db_session.commit()
    db_session.refresh(job)
    return job


# ---------------------------------------------------------------------------
# Test Exception Handling
# ---------------------------------------------------------------------------

class TestEngineExceptionHandlerExceptions:
    def test_handle_timeout_updates_status_to_timeout(self, db_session, sample_job, caplog):
        """httpx.TimeoutException -> update job TIMEOUT, log time limit exceeded"""
        handler = EngineExceptionHandler()
        exc = httpx.TimeoutException("Request timed out after 30s")

        status = handler.handle(exc, sample_job.job_uuid, db_session, computation_ms=30000)

        assert status == OptimizationJobStatus.TIMEOUT
        db_session.refresh(sample_job)
        assert sample_job.status == OptimizationJobStatus.TIMEOUT.value
        assert sample_job.computation_ms == 30000
        assert "TIMEOUT" in caplog.text or "time limit exceeded" in caplog.text.lower()

    def test_handle_http_401_updates_status_to_failed_and_logs_auth_error(self, db_session, sample_job, caplog):
        """Engine trả 401 (token thiếu/sai/expired) -> update job FAILED, log AUTH_ERROR"""
        handler = EngineExceptionHandler()
        request = httpx.Request("POST", "http://test-engine/jobs")
        response = httpx.Response(401, request=request, json={"detail": "Unauthorized"})
        exc = httpx.HTTPStatusError("401 Client Error", request=request, response=response)

        status = handler.handle(exc, sample_job.job_uuid, db_session)

        assert status == OptimizationJobStatus.FAILED
        db_session.refresh(sample_job)
        assert sample_job.status == OptimizationJobStatus.FAILED.value
        assert "AUTH_ERROR" in caplog.text

    def test_handle_http_403_updates_status_to_failed_and_logs_auth_forbidden(self, db_session, sample_job, caplog):
        """Engine trả 403 (thiếu scope optimization.execute) -> update job FAILED, log AUTH_FORBIDDEN"""
        handler = EngineExceptionHandler()
        request = httpx.Request("POST", "http://test-engine/jobs")
        response = httpx.Response(403, request=request, json={"detail": "Forbidden"})
        exc = httpx.HTTPStatusError("403 Client Error", request=request, response=response)

        status = handler.handle(exc, sample_job.job_uuid, db_session)

        assert status == OptimizationJobStatus.FAILED
        db_session.refresh(sample_job)
        assert sample_job.status == OptimizationJobStatus.FAILED.value
        assert "AUTH_FORBIDDEN" in caplog.text

    def test_handle_http_500_updates_status_to_failed(self, db_session, sample_job, caplog):
        """Engine trả 500 lỗi máy chủ -> update job FAILED, ghi log lỗi"""
        handler = EngineExceptionHandler()
        request = httpx.Request("POST", "http://test-engine/jobs")
        response = httpx.Response(500, request=request, text="Internal Server Error")
        exc = httpx.HTTPStatusError("500 Server Error", request=request, response=response)

        status = handler.handle(exc, sample_job.job_uuid, db_session)

        assert status == OptimizationJobStatus.FAILED
        db_session.refresh(sample_job)
        assert sample_job.status == OptimizationJobStatus.FAILED.value

    def test_handle_connect_error_updates_status_to_failed_and_logs_service_unavailable(self, db_session, sample_job, caplog):
        """Connection refused / network error -> update job FAILED, log SERVICE_UNAVAILABLE"""
        handler = EngineExceptionHandler()
        request = httpx.Request("POST", "http://engine-unreachable:8000/jobs")
        exc = httpx.ConnectError("Failed to connect", request=request)

        status = handler.handle(exc, sample_job.job_uuid, db_session)

        assert status == OptimizationJobStatus.FAILED
        db_session.refresh(sample_job)
        assert sample_job.status == OptimizationJobStatus.FAILED.value
        assert "SERVICE_UNAVAILABLE" in caplog.text

    def test_handle_service_authentication_exception_updates_status_to_failed(self, db_session, sample_job, caplog):
        """KeycloakTokenProvider không lấy được token -> update job FAILED, log lỗi auth"""
        handler = EngineExceptionHandler()
        exc = ServiceAuthenticationException("Cannot authenticate with Keycloak")

        status = handler.handle(exc, sample_job.job_uuid, db_session)

        assert status == OptimizationJobStatus.FAILED
        db_session.refresh(sample_job)
        assert sample_job.status == OptimizationJobStatus.FAILED.value
        assert "AUTH_ERROR" in caplog.text or "Keycloak" in caplog.text

    def test_handle_unexpected_generic_exception_does_not_crash(self, db_session, sample_job, caplog):
        """Tất cả exception không lường trước -> ghi log, update job status FAILED, không crash server"""
        handler = EngineExceptionHandler()
        exc = RuntimeError("Unexpected internal crash inside algorithm")

        status = handler.handle(exc, sample_job.job_uuid, db_session)

        assert status == OptimizationJobStatus.FAILED
        db_session.refresh(sample_job)
        assert sample_job.status == OptimizationJobStatus.FAILED.value
        assert "Unexpected internal crash" in caplog.text


# ---------------------------------------------------------------------------
# Test Result Classification & Handling
# ---------------------------------------------------------------------------

class TestEngineResultClassification:
    def test_classify_result_empty_placements_returns_no_solution(self):
        """0 placements -> update job NO_SOLUTION"""
        handler = EngineExceptionHandler()
        response = EngineOptimizationResponse(
            placements=[],
            unplaced=[
                UnplacedData(package_id=uuid.uuid4(), reason="DOES_NOT_FIT_DIMENSIONS")
            ],
            metrics=MetricsData(volume_utilization=0.0, weight_utilization=0.0, packed_count=0, computation_ms=100),
        )

        status = handler.classify_result(response)
        assert status == OptimizationJobStatus.NO_SOLUTION

    def test_classify_result_some_unplaced_returns_partial(self):
        """Partial (some unplaced) -> update job PARTIAL"""
        handler = EngineExceptionHandler()
        response = EngineOptimizationResponse(
            placements=[
                PlacementData(
                    package_id=uuid.uuid4(), x=0, y=0, z=0,
                    packed_l=1, packed_w=1, packed_h=1, rotation_type=0, step_sequence=1,
                )
            ],
            unplaced=[
                UnplacedData(package_id=uuid.uuid4(), reason="WEIGHT_LIMIT_EXCEEDED")
            ],
            metrics=MetricsData(volume_utilization=0.5, weight_utilization=0.8, packed_count=1, computation_ms=120),
        )

        status = handler.classify_result(response)
        assert status == OptimizationJobStatus.PARTIAL

    def test_classify_result_all_placed_returns_completed(self):
        """Tất cả kiện hàng được xếp -> COMPLETED"""
        handler = EngineExceptionHandler()
        response = EngineOptimizationResponse(
            placements=[
                PlacementData(
                    package_id=uuid.uuid4(), x=0, y=0, z=0,
                    packed_l=1, packed_w=1, packed_h=1, rotation_type=0, step_sequence=1,
                )
            ],
            unplaced=[],
            metrics=MetricsData(volume_utilization=0.9, weight_utilization=0.8, packed_count=1, computation_ms=150),
        )

        status = handler.classify_result(response)
        assert status == OptimizationJobStatus.COMPLETED

    def test_handle_result_updates_job_status_and_computation_ms(self, db_session, sample_job):
        """handle_result cập nhật job với status phân loại và computation_ms từ metrics"""
        handler = EngineExceptionHandler()
        response = EngineOptimizationResponse(
            placements=[],
            unplaced=[
                UnplacedData(package_id=uuid.uuid4(), reason="DOES_NOT_FIT_DIMENSIONS")
            ],
            metrics=MetricsData(volume_utilization=0.0, weight_utilization=0.0, packed_count=0, computation_ms=350),
        )

        status = handler.handle_result(response, sample_job.job_uuid, db_session)

        assert status == OptimizationJobStatus.NO_SOLUTION
        db_session.refresh(sample_job)
        assert sample_job.status == OptimizationJobStatus.NO_SOLUTION.value
        assert sample_job.computation_ms == 350


# ---------------------------------------------------------------------------
# Test Optional Notification Integration
# ---------------------------------------------------------------------------

class TestEngineExceptionHandlerNotification:
    def test_handle_calls_notification_service_if_provided(self, db_session, sample_job):
        """Nếu có notification_service, handler sẽ gọi notify_status khi có lỗi"""
        mock_notifier = MagicMock()
        handler = EngineExceptionHandler(notification_service=mock_notifier)
        exc = httpx.TimeoutException("Timeout")

        status = handler.handle(exc, sample_job.job_uuid, db_session)

        assert status == OptimizationJobStatus.TIMEOUT
        mock_notifier.notify_status.assert_called_once()
        call_args = mock_notifier.notify_status.call_args[0]
        assert call_args[0] == sample_job.job_uuid
        assert call_args[1] == OptimizationJobStatus.TIMEOUT

    def test_handle_result_calls_notification_service_if_provided(self, db_session, sample_job):
        """Nếu có notification_service, handler sẽ gọi notify_status khi có kết quả"""
        mock_notifier = MagicMock()
        handler = EngineExceptionHandler(notification_service=mock_notifier)
        response = EngineOptimizationResponse(
            placements=[
                PlacementData(
                    package_id=uuid.uuid4(), x=0, y=0, z=0,
                    packed_l=1, packed_w=1, packed_h=1, rotation_type=0, step_sequence=1,
                )
            ],
            unplaced=[],
            metrics=MetricsData(volume_utilization=0.9, weight_utilization=0.8, packed_count=1, computation_ms=200),
        )

        status = handler.handle_result(response, sample_job.job_uuid, db_session)

        assert status == OptimizationJobStatus.COMPLETED
        mock_notifier.notify_status.assert_called_once_with(
            sample_job.job_uuid,
            OptimizationJobStatus.COMPLETED,
            computation_ms=200,
        )
