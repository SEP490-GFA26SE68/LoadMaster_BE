"""
Tests for S3-04 · OptimizationController

Seams under test:
  - POST /api/v1/optimization/jobs -> 202 Accepted + {"job_id": ...}
  - GET  /api/v1/optimization/jobs/{id} -> 200 + status & metadata
  - GET  /api/v1/optimization/jobs/{id}/plans -> 200 + list of LoadPlans
  - Auth verification: require_optimization_service (service token của LoadMasterService)

Acceptance Criteria:
  1. POST /api/v1/optimization/jobs — body { trip_id, objective, time_limit_sec, seed } -> 202 + { job_id }
  2. GET /api/v1/optimization/jobs/{id} -> status, metadata
  3. GET /api/v1/optimization/jobs/{id}/plans -> LoadPlan + placements
  4. Chỉ service token của LoadMasterService gọi được (role OPTIMIZATION_EXECUTE, azp của backend)
"""
import uuid
import pytest
from starlette.testclient import TestClient

from app.main import app
from app.config.database import get_db
from app.constant.optimization.job_status import OptimizationJobStatus
from app.constant.optimization.objective import OptimizationObjective
from app.entity.optimization.optimization_job import OptimizationJob
from app.entity.trip_model import Trip
from tests.keycloak_tokens import keycloak_token, service_headers, user_headers


# Ba endpoint job chỉ nhận service token của LoadMasterService (require_optimization_service): token ký RS256 như
# Keycloak, chỉ JWKS được thay bằng khoá của test (fixture keycloak_jwks).
pytestmark = pytest.mark.usefixtures("keycloak_jwks")


@pytest.fixture
def client():
    return TestClient(app)


@pytest.fixture
def dispatcher_headers():
    return service_headers()


@pytest.fixture
def admin_headers():
    return service_headers()


@pytest.fixture
def driver_headers():
    return user_headers("DRIVER")


class TestOptimizationAuth:
    def test_submit_job_without_token_returns_401(self, client):
        """Không có token -> 401 Unauthorized"""
        response = client.post(
            "/api/v1/optimization/jobs",
            json={"trip_id": 404, "objective": "MAX_VOLUME"},
        )
        assert response.status_code == 401

    def test_submit_job_with_unauthorized_role_returns_403(self, client, driver_headers):
        """User có role DRIVER (không phải DISPATCHER/ADMIN) -> 403 Forbidden"""
        response = client.post(
            "/api/v1/optimization/jobs",
            json={"trip_id": 404, "objective": "MAX_VOLUME"},
            headers=driver_headers,
        )
        assert response.status_code == 403


    def test_user_token_with_dispatcher_role_returns_403(self, client):
        """Token người dùng (azp loadmaster-web) dù có role đúng cũng không gọi được: chỉ service token của backend."""
        token = keycloak_token(["OPTIMIZATION_EXECUTE", "DISPATCHER"])
        response = client.post(
            "/api/v1/optimization/jobs",
            json={"trip_id": 404, "objective": "MAX_VOLUME"},
            headers={"Authorization": f"Bearer {token}"},
        )
        assert response.status_code == 403

    def test_token_signed_by_another_key_returns_401(self, client):
        """Chữ ký không khớp khoá của Keycloak -> 401."""
        import jwt as pyjwt
        forged = pyjwt.encode({"sub": "x"}, "not-the-keycloak-key-32-bytes-long!!", algorithm="HS256")
        response = client.post(
            "/api/v1/optimization/jobs",
            json={"trip_id": 404, "objective": "MAX_VOLUME"},
            headers={"Authorization": f"Bearer {forged}"},
        )
        assert response.status_code == 401


class TestSubmitJob:
    def test_submit_job_success_returns_202_and_job_id(self, client, dispatcher_headers):
        """DISPATCHER gửi job hợp lệ -> 202 Accepted + { job_id }"""
        trip_id = 123
        mock_job_uuid = str(uuid.uuid4())

        from app.service.optimize.optimization_job_service import OptimizationJobService

        class MockJobService(OptimizationJobService):
            def create_job(self, trip_id, config, db):
                return OptimizationJob(
                    id=1,
                    trip_id=trip_id,
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
            payload = {"trip_id": 404, "objective": "MAX_VOLUME"}
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
                    trip_id=123,
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
            assert data["trip_id"] == 123
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
