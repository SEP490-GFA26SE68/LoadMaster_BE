"""
Chạy lại giữ kiện đã ghim và duyệt phương án bằng service token của Spring Boot.

Seams:
  - OptimizationClient.solve_in_process(problem) với problem.pinned
  - POST /api/v1/optimize/rerun (service token, thân camelCase của Java)
  - POST /api/v1/load-plans/{id}/approve (service token + approved_by_user_id trong thân)
"""
from types import SimpleNamespace

from starlette.testclient import TestClient

from app.config.database import get_db
from app.config.settings import settings
from app.controller.optimization import load_plan_router, rerun_router
from app.dto.optimization.engine.problem_request import (
    PackageData,
    PinnedData,
    ProblemRequest,
    StopData,
    VehicleData,
)
from app.dto.response.load_plan_response import LoadPlanResponse
from app.main import app
from app.service.auth.auth_dependency import (
    is_backend_service,
    require_optimization_service,
    require_plan_approver,
)
from app.service.optimize.optimization_client import OptimizationClient


def vehicle() -> VehicleData:
    return VehicleData(
        inner_l=600, inner_w=240, inner_h=240, max_payload_kg=5000,
        door_w=240, door_h=240,
        front_axle_limit_kg=10000, rear_axle_limit_kg=10000,
        front_axle_x=-100, rear_axle_x=400,
        empty_front_axle_load_kg=2000, empty_rear_axle_load_kg=3000,
    )


def packages() -> list[PackageData]:
    return [
        PackageData(id=str(i), l=100, w=100, h=100, weight=50, stop_index=1,
                    allowed_rotations=[0], rotation_allowed=False)
        for i in (1, 2, 3, 4)
    ]


def problem(pinned=None) -> ProblemRequest:
    return ProblemRequest(
        vehicle=vehicle(),
        packages=packages(),
        stops=[StopData(id=7, sequence=1)],
        pinned=pinned or [],
    )


def overlaps(a, b) -> bool:
    return not (
        a.x >= b.x + b.packed_l or a.x + a.packed_l <= b.x
        or a.y >= b.y + b.packed_w or a.y + a.packed_w <= b.y
        or a.z >= b.z + b.packed_h or a.z + a.packed_h <= b.z
    )


SERVICE_CLAIMS = {
    "azp": settings.KEYCLOAK_ALLOWED_CLIENT_ID,
    "resource_access": {
        settings.KEYCLOAK_AUDIENCE: {"roles": [settings.KEYCLOAK_REQUIRED_ROLE]},
    },
}

USER_CLAIMS = {
    "azp": "loadmaster-web",
    "user_id": 456,
    "resource_access": {settings.KEYCLOAK_AUDIENCE: {"roles": ["DISPATCHER"]}},
}


class TestPinnedSolver:
    def test_pinned_package_keeps_position_and_others_avoid_it(self):
        pin = PinnedData(package_id="2", pos_x=250, pos_y=70, pos_z=0)
        result = OptimizationClient().solve_in_process(problem([pin]))

        by_id = {str(p.package_id): p for p in result.placements}
        assert set(by_id) == {"1", "2", "3", "4"}
        pinned = by_id["2"]
        assert (pinned.x, pinned.y, pinned.z) == (250, 70, 0)
        assert (pinned.packed_l, pinned.packed_w, pinned.packed_h) == (100, 100, 100)
        assert pinned.pinned is True
        assert pinned.stop_zone_id == 7
        for key in ("1", "3", "4"):
            assert by_id[key].pinned is False
            assert not overlaps(by_id[key], pinned)

    def test_pinned_package_outside_trip_is_ignored(self):
        pin = PinnedData(package_id="999", pos_x=0, pos_y=0, pos_z=0)
        result = OptimizationClient().solve_in_process(problem([pin]))

        assert {str(p.package_id) for p in result.placements} == {"1", "2", "3", "4"}
        assert all(p.pinned is False for p in result.placements)

    def test_pinned_rotation_derives_packed_size(self):
        long_box = PackageData(id="9", l=200, w=100, h=50, weight=20, stop_index=1,
                               allowed_rotations=[0, 1], rotation_allowed=True)
        req = ProblemRequest(
            vehicle=vehicle(),
            packages=[long_box],
            stops=[StopData(id=7, sequence=1)],
            pinned=[PinnedData(package_id="9", pos_x=200, pos_y=0, pos_z=0, rotation_type=1)],
        )
        result = OptimizationClient().solve_in_process(req)

        placed = result.placements[0]
        assert (placed.packed_l, placed.packed_w, placed.packed_h) == (100, 200, 50)
        assert placed.rotation_type == 1


class FakeRunner:
    def __init__(self):
        self.calls = []

    def build_problem_request_for_trip(self, trip_id, objective, time_limit_sec, db):
        self.calls.append((trip_id, objective, time_limit_sec))
        return problem()


class TestRerunApi:
    def teardown_method(self):
        app.dependency_overrides.clear()

    def test_rerun_without_token_returns_401(self):
        app.dependency_overrides[get_db] = lambda: None
        response = TestClient(app).post("/api/v1/optimize/rerun", json={"tripId": 5})
        assert response.status_code == 401

    def test_rerun_builds_from_trip_keeps_pins_and_returns_java_fields(self):
        runner = FakeRunner()
        app.dependency_overrides[get_db] = lambda: None
        app.dependency_overrides[require_optimization_service] = lambda: SERVICE_CLAIMS
        app.dependency_overrides[rerun_router.get_runner] = lambda: runner
        app.dependency_overrides[rerun_router.get_solver] = lambda: OptimizationClient(engine_url="")

        response = TestClient(app).post("/api/v1/optimize/rerun", json={
            "jobUuid": "job-1",
            "tripId": 5,
            "parentPlanId": 11,
            "objective": "MAX_VOLUME",
            "timeLimitSec": 60,
            "pinnedPlacements": [{
                "placementId": 101, "packageId": 2, "posX": 250, "posY": 70, "posZ": 0,
                "packedLength": 100, "packedWidth": 100, "packedHeight": 100,
                "rotationType": 0, "stepSequence": 1,
            }],
        })

        assert response.status_code == 200
        body = response.json()
        assert body["success"] is True
        assert runner.calls == [(5, "MAX_VOLUME", 60)]
        data = body["data"]
        assert data["packedItemsCount"] == 4
        placements = {p["packageId"]: p for p in data["placements"]}
        assert set(placements) == {1, 2, 3, 4}
        pinned = placements[2]
        assert pinned["pinned"] is True
        assert (pinned["posX"], pinned["posY"], pinned["posZ"]) == (250, 70, 0)
        assert pinned["stopZoneId"] == 7
        assert all(isinstance(p["posX"], int) for p in data["placements"])
        assert data["unplaced"] == []


class FakePlanService:
    def __init__(self):
        self.approved = []
        self.company_checks = []

    def ensure_plans_of_company(self, plan_ids, company_id, db):
        self.company_checks.append(list(plan_ids))

    def approve_plan(self, plan_id, current_user_id, db):
        self.approved.append((plan_id, current_user_id))
        return SimpleNamespace(id=plan_id)

    def to_response(self, plan, db):
        return LoadPlanResponse(id=plan.id, approved=True, approved_by_id=self.approved[-1][1])


class TestServiceApproval:
    def teardown_method(self):
        app.dependency_overrides.clear()

    def test_backend_service_claims_are_recognised(self):
        assert is_backend_service(SERVICE_CLAIMS) is True
        assert is_backend_service(USER_CLAIMS) is False
        wrong_client = {**SERVICE_CLAIMS, "azp": "loadmaster-web"}
        assert is_backend_service(wrong_client) is False

    def _client(self, claims, service):
        app.dependency_overrides[get_db] = lambda: None
        app.dependency_overrides[require_plan_approver] = lambda: claims
        app.dependency_overrides[load_plan_router.get_load_plan_service] = lambda: service
        return TestClient(app)

    def test_service_token_approves_as_user_in_body(self):
        service = FakePlanService()
        response = self._client(SERVICE_CLAIMS, service).post(
            "/api/v1/load-plans/12/approve", json={"approved_by_user_id": 77})

        assert response.status_code == 200
        assert service.approved == [(12, 77)]
        # Service token: Java đã kiểm công ty
        assert service.company_checks == []
        assert response.json()["data"]["approved_by_id"] == 77

    def test_service_token_without_approver_is_rejected(self):
        service = FakePlanService()
        response = self._client(SERVICE_CLAIMS, service).post("/api/v1/load-plans/12/approve")

        assert response.status_code == 400
        assert service.approved == []

    def test_user_token_approves_as_token_user(self):
        service = FakePlanService()
        response = self._client(USER_CLAIMS, service).post(
            "/api/v1/load-plans/12/approve", json={"approved_by_user_id": 1})

        assert response.status_code == 200
        assert service.approved == [(12, 456)]
        # Token người dùng: kiểm công ty của phương án trước khi duyệt
        assert service.company_checks == [[12]]

    def test_approve_without_token_returns_401(self):
        app.dependency_overrides[get_db] = lambda: None
        response = TestClient(app).post("/api/v1/load-plans/12/approve")
        assert response.status_code == 401
