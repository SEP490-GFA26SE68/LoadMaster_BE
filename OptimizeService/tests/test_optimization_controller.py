"""
Tests for S3-04 · OptimizationController

Seams under test:
  - POST /api/v1/optimization/jobs -> 202 Accepted + numeric {"job_id": ...}
  - GET  /api/v1/optimization/jobs/{id} -> 200 + status & metadata
  - GET  /api/v1/optimization/jobs/{id}/plans -> 200 + list of LoadPlans
  - Auth verification: require_role("DISPATCHER", "ADMIN")

Acceptance Criteria:
  1. POST /api/v1/optimization/jobs — body { trip_id, objective, time_limit_sec, seed } -> 202 + numeric { job_id }
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
from app.exception.app_exception import AppException
from app.exception.error_code import ErrorCode
from app.service.optimize.optimization_job_service import OptimizationJobService
from app.controller.optimization.optimization_router import (
    get_optimization_job_service,
    get_load_plan_service,
    get_async_runner,
)
from app.dto.response.load_plan_response import LoadPlanResponse
from app.dto.response.package_placement_response import PackagePlacementResponse
from app.service.optimize.load_plan_service import LoadPlanService


class MockRunner:
    async def run(self, *args, **kwargs):
        pass


def make_jwt_token(roles: list[str]) -> str:
    """Tạo JWT token giả lập từ Keycloak chứa roles (dùng key >= 32 bytes để tránh warning)."""
    payload = {
        "iss": "http://localhost:8080/realms/loadmaster",
        "sub": "user-123",
        "realm_access": {"roles": roles},
    }
    return jwt.encode(payload, "secret-test-key-must-be-at-least-32-bytes-long", algorithm="HS256")


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
        """POST /jobs: Không có token -> 401 Unauthorized"""
        response = client.post(
            "/api/v1/optimization/jobs",
            json={"trip_id": 3501, "objective": "MAX_VOLUME"},
        )
        assert response.status_code == 401

    def test_submit_job_with_unauthorized_role_returns_403(self, client, driver_headers):
        """POST /jobs: Role DRIVER (không phải DISPATCHER/ADMIN) -> 403 Forbidden"""
        response = client.post(
            "/api/v1/optimization/jobs",
            json={"trip_id": 3501, "objective": "MAX_VOLUME"},
            headers=driver_headers,
        )
        assert response.status_code == 403

    def test_get_job_without_token_returns_401(self, client):
        """GET /jobs/{id}: Không có token -> 401 Unauthorized"""
        response = client.get("/api/v1/optimization/jobs/1")
        assert response.status_code == 401

    def test_get_job_with_unauthorized_role_returns_403(self, client, driver_headers):
        """GET /jobs/{id}: Role DRIVER -> 403 Forbidden"""
        response = client.get("/api/v1/optimization/jobs/1", headers=driver_headers)
        assert response.status_code == 403

    def test_get_job_plans_without_token_returns_401(self, client):
        """GET /jobs/{id}/plans: Không có token -> 401 Unauthorized"""
        response = client.get("/api/v1/optimization/jobs/1/plans")
        assert response.status_code == 401

    def test_get_job_plans_with_unauthorized_role_returns_403(self, client, driver_headers):
        """GET /jobs/{id}/plans: Role DRIVER -> 403 Forbidden"""
        response = client.get("/api/v1/optimization/jobs/1/plans", headers=driver_headers)
        assert response.status_code == 403


class TestSubmitJob:
    def test_submit_job_success_returns_202_and_numeric_job_id(self, client, dispatcher_headers):
        """DISPATCHER gửi job hợp lệ -> 202 Accepted + numeric { job_id }"""
        class MockJobService(OptimizationJobService):
            def create_job(self, trip_id, config, db):
                return OptimizationJob(
                    id=101,
                    trip_id=int(trip_id),
                    status=OptimizationJobStatus.PENDING.value,
                    algorithm_objective=config.objective.value if hasattr(config.objective, "value") else str(config.objective),
                )

        app.dependency_overrides[get_optimization_job_service] = lambda: MockJobService()
        app.dependency_overrides[get_async_runner] = lambda: MockRunner()
        app.dependency_overrides[get_db] = lambda: None

        try:
            payload = {
                "trip_id": 3501,
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
            assert data["job_id"] == 101
            assert isinstance(data["job_id"], int)
        finally:
            app.dependency_overrides.clear()

    def test_submit_job_success_with_admin_role(self, client, admin_headers):
        """ADMIN gửi job hợp lệ -> 202 Accepted + numeric { job_id }"""
        class MockJobService(OptimizationJobService):
            def create_job(self, trip_id, config, db):
                return OptimizationJob(
                    id=102,
                    trip_id=int(trip_id),
                    status=OptimizationJobStatus.PENDING.value,
                    algorithm_objective="AXLE_BALANCE",
                )

        app.dependency_overrides[get_optimization_job_service] = lambda: MockJobService()
        app.dependency_overrides[get_async_runner] = lambda: MockRunner()
        app.dependency_overrides[get_db] = lambda: None

        try:
            payload = {
                "trip_id": "3502",
                "objective": "AXLE_BALANCE",
            }
            response = client.post(
                "/api/v1/optimization/jobs",
                json=payload,
                headers=admin_headers,
            )
            assert response.status_code == 202
            body = response.json()
            assert body["success"] is True
            assert body["data"]["job_id"] == 102
        finally:
            app.dependency_overrides.clear()

    def test_submit_job_with_non_existent_trip_returns_404(self, client, admin_headers):
        """Gửi job với trip không tồn tại -> 404 TRIP_NOT_FOUND"""
        class MockJobService(OptimizationJobService):
            def create_job(self, trip_id, config, db):
                raise AppException(ErrorCode.TRIP_NOT_FOUND)

        app.dependency_overrides[get_optimization_job_service] = lambda: MockJobService()
        app.dependency_overrides[get_async_runner] = lambda: MockRunner()
        app.dependency_overrides[get_db] = lambda: None

        try:
            payload = {"trip_id": 99999, "objective": "MAX_VOLUME"}
            response = client.post(
                "/api/v1/optimization/jobs",
                json=payload,
                headers=admin_headers,
            )
            assert response.status_code == 404
            assert response.json()["code"] == "TRIP_NOT_FOUND"
        finally:
            app.dependency_overrides.clear()

    def test_submit_job_with_invalid_payload_returns_422(self, client, dispatcher_headers):
        """time_limit_sec < 10 -> 422 Unprocessable Entity"""
        payload = {
            "trip_id": 3501,
            "objective": "MAX_VOLUME",
            "time_limit_sec": 5,
        }
        response = client.post(
            "/api/v1/optimization/jobs",
            json=payload,
            headers=dispatcher_headers,
        )
        assert response.status_code == 422


class TestGetJobStatus:
    def test_get_job_success_returns_200_and_status(self, client, dispatcher_headers):
        """Lấy thông tin job theo numeric ID -> 200 + status, metadata"""
        class MockJobService(OptimizationJobService):
            def get_job(self, j_id, db):
                return OptimizationJob(
                    id=10,
                    trip_id=3501,
                    status=OptimizationJobStatus.RUNNING.value,
                    algorithm_objective="MAX_VOLUME",
                    execution_time_ms=1250,
                )

        app.dependency_overrides[get_optimization_job_service] = lambda: MockJobService()
        app.dependency_overrides[get_db] = lambda: None

        try:
            response = client.get(
                "/api/v1/optimization/jobs/10",
                headers=dispatcher_headers,
            )
            assert response.status_code == 200
            body = response.json()
            assert body["success"] is True
            data = body["data"]
            assert data["id"] == 10
            assert data["status"] == "RUNNING"
            assert data["trip_id"] == 3501
            assert data["algorithm_objective"] == "MAX_VOLUME"
            assert data["execution_time_ms"] == 1250
            # CamelCase alias properties
            assert data["tripId"] == 3501
            assert data["algorithmObjective"] == "MAX_VOLUME"
            assert data["executionTimeMs"] == 1250
        finally:
            app.dependency_overrides.clear()

    def test_get_job_success_with_admin_role(self, client, admin_headers):
        """ADMIN lấy thông tin job thành công -> 200"""
        class MockJobService(OptimizationJobService):
            def get_job(self, j_id, db):
                return OptimizationJob(
                    id=20,
                    trip_id=3502,
                    status=OptimizationJobStatus.COMPLETED.value,
                    algorithm_objective="AXLE_BALANCE",
                    execution_time_ms=800,
                )

        app.dependency_overrides[get_optimization_job_service] = lambda: MockJobService()
        app.dependency_overrides[get_db] = lambda: None

        try:
            response = client.get(
                "/api/v1/optimization/jobs/20",
                headers=admin_headers,
            )
            assert response.status_code == 200
            assert response.json()["data"]["id"] == 20
            assert response.json()["data"]["status"] == "COMPLETED"
        finally:
            app.dependency_overrides.clear()

    def test_get_job_not_found_returns_404(self, client, dispatcher_headers):
        """Lấy job không tồn tại -> 404 OPTIMIZATION_JOB_NOT_FOUND"""
        class MockJobService(OptimizationJobService):
            def get_job(self, j_id, db):
                raise AppException(ErrorCode.OPTIMIZATION_JOB_NOT_FOUND)

        app.dependency_overrides[get_optimization_job_service] = lambda: MockJobService()
        app.dependency_overrides[get_db] = lambda: None

        try:
            response = client.get(
                "/api/v1/optimization/jobs/99999",
                headers=dispatcher_headers,
            )
            assert response.status_code == 404
            assert response.json()["code"] == "OPTIMIZATION_JOB_NOT_FOUND"
        finally:
            app.dependency_overrides.clear()


class TestGetJobPlans:
    def test_get_job_plans_success_returns_200_and_plans(self, client, dispatcher_headers):
        """Lấy danh sách plans của job -> 200 + danh sách LoadPlan"""
        class MockLoadPlanService(LoadPlanService):
            def get_plans_for_job(self, j_id, db):
                return [
                    LoadPlanResponse(
                        id=1,
                        plan_name="Plan Optimal 1",
                        packed_items_count=10,
                        volume_utilization=0.88,
                        weight_utilization=0.75,
                        approved=False,
                        placements=[
                            PackagePlacementResponse(
                                id=1,
                                package_id="101",
                                step_sequence=1,
                                pos_x=0.0,
                                pos_y=0.0,
                                pos_z=0.0,
                                dim_x=1.0,
                                dim_y=1.0,
                                dim_z=1.0,
                            )
                        ],
                    )
                ]

        app.dependency_overrides[get_load_plan_service] = lambda: MockLoadPlanService()
        app.dependency_overrides[get_db] = lambda: None

        try:
            response = client.get(
                "/api/v1/optimization/jobs/10/plans",
                headers=dispatcher_headers,
            )
            assert response.status_code == 200
            body = response.json()
            assert body["success"] is True
            plans = body["data"]
            assert len(plans) == 1
            assert plans[0]["id"] == 1
            assert plans[0]["plan_name"] == "Plan Optimal 1"
            assert plans[0]["packed_items_count"] == 10
            assert len(plans[0]["placements"]) == 1
            assert plans[0]["placements"][0]["package_id"] == "101"
        finally:
            app.dependency_overrides.clear()

    def test_get_job_plans_empty_returns_200_and_empty_list(self, client, dispatcher_headers):
        """Job chưa có kế hoạch -> 200 + []"""
        class MockLoadPlanService(LoadPlanService):
            def get_plans_for_job(self, j_id, db):
                return []

        app.dependency_overrides[get_load_plan_service] = lambda: MockLoadPlanService()
        app.dependency_overrides[get_db] = lambda: None

        try:
            response = client.get(
                "/api/v1/optimization/jobs/10/plans",
                headers=dispatcher_headers,
            )
            assert response.status_code == 200
            body = response.json()
            assert body["success"] is True
            assert body["data"] == []
        finally:
            app.dependency_overrides.clear()
