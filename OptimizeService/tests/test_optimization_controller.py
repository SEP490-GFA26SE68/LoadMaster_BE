"""
Tests for S3-04 · OptimizationController

Seams under test:
  - POST /api/v1/optimization/jobs -> 202 Accepted + {"job_id": ...}
  - GET  /api/v1/optimization/jobs/{id} -> 200 + status & metadata
  - GET  /api/v1/optimization/jobs/{id}/plans -> 200 + list of LoadPlans
  - Auth verification: require_role("DISPATCHER", "ADMIN")

Acceptance Criteria:
  1. POST /api/v1/optimization/jobs — body { trip_id, objective, time_limit_sec, seed } -> 202 + { job_id }
  2. GET /api/v1/optimization/jobs/{id} -> status, metadata
  3. GET /api/v1/optimization/jobs/{id}/plans -> LoadPlan + placements
  4. Chỉ DISPATCHER / ADMIN mới được gọi (FastAPI verify JWT role)
"""
import uuid
import pytest
import jwt
from starlette.testclient import TestClient

from app.main import app
from app.config.database import get_db
from app.constant.optimization.job_status import OptimizationJobStatus
from app.constant.optimization.objective import OptimizationObjective
from app.entity.optimization.optimization_job import OptimizationJob
from app.entity.trip_model import Trip
from app.service.auth.auth_dependency import require_role


def make_jwt_token(roles: list[str]) -> str:
    """Tạo JWT token giả lập từ Keycloak chứa roles."""
    payload = {
        "iss": "http://localhost:8080/realms/loadmaster",
        "sub": "user-123",
        "realm_access": {"roles": roles},
    }
    return jwt.encode(payload, "secret-test-key", algorithm="HS256")


@pytest.fixture
def client():
    return TestClient(app)


@pytest.fixture
def dispatcher_headers():
    token = make_jwt_token(["DISPATCHER"])
    return {"Authorization": f"Bearer {token}"}


@pytest.fixture
def admin_headers():
    token = make_jwt_token(["ADMIN"])
    return {"Authorization": f"Bearer {token}"}


@pytest.fixture
def driver_headers():
    token = make_jwt_token(["DRIVER"])
    return {"Authorization": f"Bearer {token}"}


class TestOptimizationAuth:
    def test_submit_job_without_token_returns_401(self, client):
        """Không có token -> 401 Unauthorized"""
        response = client.post(
            "/api/v1/optimization/jobs",
            json={"trip_id": str(uuid.uuid4()), "objective": "MAX_VOLUME"},
        )
        assert response.status_code == 401

    def test_submit_job_with_unauthorized_role_returns_403(self, client, driver_headers):
        """User có role DRIVER (không phải DISPATCHER/ADMIN) -> 403 Forbidden"""
        response = client.post(
            "/api/v1/optimization/jobs",
            json={"trip_id": str(uuid.uuid4()), "objective": "MAX_VOLUME"},
            headers=driver_headers,
        )
        assert response.status_code == 403


class TestSubmitJob:
    def test_submit_job_success_returns_202_and_job_id(self, client, dispatcher_headers):
        """DISPATCHER gửi job hợp lệ -> 202 Accepted + { job_id }"""
        trip_id = str(uuid.uuid4())
        mock_job_uuid = str(uuid.uuid4())

        from app.service.optimize.optimization_job_service import OptimizationJobService

        class MockJobService(OptimizationJobService):
            def create_job(self, trip_id, config, db):
                return OptimizationJob(
                    id=1,
                    trip_id=str(trip_id),
                    job_uuid=mock_job_uuid,
                    status=OptimizationJobStatus.PENDING.value,
                    objective=config.objective.value if hasattr(config.objective, "value") else str(config.objective),
                )

        from app.controller.optimization.optimization_router import get_optimization_job_service
        app.dependency_overrides[get_optimization_job_service] = lambda: MockJobService()
        app.dependency_overrides[get_db] = lambda: None

        try:
            payload = {
                "trip_id": trip_id,
                "objective": "MAX_VOLUME",
                "time_limit_sec": 60,
                "seed": 42,
            }
            response = client.post(
                "/api/v1/optimization/jobs",
                json=payload,
                headers=dispatcher_headers,
            )
            assert response.status_code == 202
            body = response.json()
            assert body["success"] is True
            data = body["data"]
            assert "job_id" in data
            assert data["job_id"] == mock_job_uuid
        finally:
            app.dependency_overrides.clear()

    def test_submit_job_with_non_existent_trip_returns_404(self, client, admin_headers):
        """ADMIN gửi job với trip không tồn tại -> 404 TRIP_NOT_FOUND"""
        from app.service.optimize.optimization_job_service import OptimizationJobService
        from app.exception.app_exception import AppException
        from app.exception.error_code import ErrorCode

        class MockJobService(OptimizationJobService):
            def create_job(self, trip_id, config, db):
                raise AppException(ErrorCode.TRIP_NOT_FOUND)

        from app.controller.optimization.optimization_router import get_optimization_job_service
        app.dependency_overrides[get_optimization_job_service] = lambda: MockJobService()
        app.dependency_overrides[get_db] = lambda: None

        try:
            payload = {"trip_id": str(uuid.uuid4()), "objective": "MAX_VOLUME"}
            response = client.post(
                "/api/v1/optimization/jobs",
                json=payload,
                headers=admin_headers,
            )
            assert response.status_code == 404
            assert response.json()["code"] == "TRIP_NOT_FOUND"
        finally:
            app.dependency_overrides.clear()


class TestGetJobStatus:
    def test_get_job_success_returns_200_and_status(self, client, dispatcher_headers):
        """Lấy thông tin job -> 200 + status, metadata"""
        job_uuid = str(uuid.uuid4())
        from app.service.optimize.optimization_job_service import OptimizationJobService

        class MockJobService(OptimizationJobService):
            def get_job(self, j_uuid, db):
                return OptimizationJob(
                    id=10,
                    job_uuid=job_uuid,
                    trip_id="trip-123",
                    status=OptimizationJobStatus.RUNNING.value,
                    objective="MAX_VOLUME",
                    time_limit_sec=60,
                )

        from app.controller.optimization.optimization_router import get_optimization_job_service
        app.dependency_overrides[get_optimization_job_service] = lambda: MockJobService()
        app.dependency_overrides[get_db] = lambda: None

        try:
            response = client.get(
                f"/api/v1/optimization/jobs/{job_uuid}",
                headers=dispatcher_headers,
            )
            assert response.status_code == 200
            body = response.json()
            assert body["success"] is True
            data = body["data"]
            assert data["job_uuid"] == job_uuid
            assert data["status"] == "RUNNING"
            assert data["trip_id"] == "trip-123"
        finally:
            app.dependency_overrides.clear()

    def test_get_job_not_found_returns_404(self, client, dispatcher_headers):
        """Lấy job không tồn tại -> 404 OPTIMIZATION_JOB_NOT_FOUND"""
        from app.service.optimize.optimization_job_service import OptimizationJobService
        from app.exception.app_exception import AppException
        from app.exception.error_code import ErrorCode

        class MockJobService(OptimizationJobService):
            def get_job(self, j_uuid, db):
                raise AppException(ErrorCode.OPTIMIZATION_JOB_NOT_FOUND)

        from app.controller.optimization.optimization_router import get_optimization_job_service
        app.dependency_overrides[get_optimization_job_service] = lambda: MockJobService()
        app.dependency_overrides[get_db] = lambda: None

        try:
            response = client.get(
                f"/api/v1/optimization/jobs/{uuid.uuid4()}",
                headers=dispatcher_headers,
            )
            assert response.status_code == 404
            assert response.json()["code"] == "OPTIMIZATION_JOB_NOT_FOUND"
        finally:
            app.dependency_overrides.clear()


class TestGetJobPlans:
    def test_get_job_plans_success_returns_200_and_plans(self, client, dispatcher_headers):
        """Lấy danh sách plans của job -> 200 + danh sách LoadPlan"""
        job_uuid = str(uuid.uuid4())
        from app.controller.optimization.optimization_router import get_load_plan_service
        from app.service.optimize.load_plan_service import LoadPlanService
        from app.dto.response.load_plan_response import LoadPlanResponse

        class MockLoadPlanService(LoadPlanService):
            def get_plans_for_job(self, j_uuid, db):
                return [
                    LoadPlanResponse(
                        id=1,
                        plan_name="Plan Optimal 1",
                        packed_items_count=10,
                        volume_utilization=0.88,
                        weight_utilization=0.75,
                        approved=False,
                        placements=[],
                    )
                ]

        app.dependency_overrides[get_load_plan_service] = lambda: MockLoadPlanService()
        app.dependency_overrides[get_db] = lambda: None

        try:
            response = client.get(
                f"/api/v1/optimization/jobs/{job_uuid}/plans",
                headers=dispatcher_headers,
            )
            assert response.status_code == 200
            body = response.json()
            assert body["success"] is True
            plans = body["data"]
            assert len(plans) == 1
            assert plans[0]["plan_name"] == "Plan Optimal 1"
            assert plans[0]["packed_items_count"] == 10
        finally:
            app.dependency_overrides.clear()
