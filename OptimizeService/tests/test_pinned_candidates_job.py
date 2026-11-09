"""
Lần chạy ba phương án giữ kiện đã ghim (POST /api/v1/optimization/jobs có pinned_placements).

Seams: pinned_set_issues (bộ ghim phải đứng vững một mình), OptimizationClient.solve_candidates
(kiện ghim là hộp cố định của mọi cách xếp), submit_job (bộ ghim hỏng: 400 PINNED_SET_INVALID,
không tạo job nên không trừ credit). Đơn vị: cm, kg.
"""
from starlette.testclient import TestClient

from app.config.database import get_db
from app.controller.optimization.optimization_router import get_async_runner, get_optimization_job_service
from app.dto.optimization.engine.problem_request import (
    PackageData,
    PinnedData,
    ProblemRequest,
    StopData,
    VehicleData,
)
from app.main import app
from app.service.auth.auth_dependency import require_optimization_service
from app.service.optimize.optimization_client import OptimizationClient
from app.service.optimize.pinned_set_validator import pinned_set_issues


def vehicle(payload: float = 5000) -> VehicleData:
    return VehicleData(
        inner_l=600, inner_w=240, inner_h=240, max_payload_kg=payload,
        door_w=240, door_h=240,
        front_axle_limit_kg=10000, rear_axle_limit_kg=10000,
        front_axle_x=-100, rear_axle_x=400,
        empty_front_axle_load_kg=2000, empty_rear_axle_load_kg=3000,
    )


def packages() -> list[PackageData]:
    return [
        PackageData(id=str(i), l=100, w=100, h=100, weight=50, stop_index=1,
                    allowed_rotations=[0], rotation_allowed=False, max_stack_weight_kg=200)
        for i in (1, 2, 3, 4)
    ]


def problem(pinned, payload: float = 5000) -> ProblemRequest:
    return ProblemRequest(
        vehicle=vehicle(payload),
        packages=packages(),
        stops=[StopData(id=7, sequence=1)],
        pinned=pinned,
        time_limit_sec=30,
    )


def codes(req: ProblemRequest) -> set[str]:
    return {issue.code for issue in pinned_set_issues(req)}


class TestPinnedSetIssues:
    def test_valid_set_has_no_issue(self):
        pins = [
            PinnedData(package_id="1", pos_x=0, pos_y=0, pos_z=0),
            PinnedData(package_id="2", pos_x=0, pos_y=0, pos_z=100),
        ]
        assert pinned_set_issues(problem(pins)) == []
        assert pinned_set_issues(problem([])) == []

    def test_unknown_and_duplicate_packages(self):
        pins = [
            PinnedData(package_id="999", pos_x=0, pos_y=0, pos_z=0),
            PinnedData(package_id="1", pos_x=0, pos_y=0, pos_z=0),
            PinnedData(package_id="1", pos_x=200, pos_y=0, pos_z=0),
        ]
        assert codes(problem(pins)) >= {"PINNED_PACKAGE_UNKNOWN", "PINNED_DUPLICATE"}

    def test_out_of_bounds_overlap_and_floating_are_rejected(self):
        outside = [PinnedData(package_id="1", pos_x=550, pos_y=0, pos_z=0)]
        assert codes(problem(outside))

        overlapping = [
            PinnedData(package_id="1", pos_x=0, pos_y=0, pos_z=0),
            PinnedData(package_id="2", pos_x=50, pos_y=0, pos_z=0),
        ]
        assert codes(problem(overlapping))

        floating = [PinnedData(package_id="1", pos_x=0, pos_y=0, pos_z=80)]
        assert codes(problem(floating))

    def test_payload_exceeded(self):
        pins = [
            PinnedData(package_id="1", pos_x=0, pos_y=0, pos_z=0),
            PinnedData(package_id="2", pos_x=200, pos_y=0, pos_z=0),
        ]
        assert codes(problem(pins, payload=60))


class TestCandidatesKeepPins:
    def test_every_candidate_keeps_the_pin_first_and_others_avoid_it(self):
        pin = PinnedData(package_id="2", pos_x=250, pos_y=70, pos_z=0)
        result = OptimizationClient(engine_url="").solve_candidates(problem([pin]))

        assert len(result.candidates) == 3
        for candidate in result.candidates:
            placements = candidate.result.placements
            head = placements[0]
            assert str(head.package_id) == "2"
            assert head.pinned is True
            assert (head.x, head.y, head.z) == (250, 70, 0)
            assert {str(p.package_id) for p in placements} == {"1", "2", "3", "4"}
            for other in placements[1:]:
                assert other.pinned is False
                assert (
                    other.x >= head.x + head.packed_l or other.x + other.packed_l <= head.x
                    or other.y >= head.y + head.packed_w or other.y + other.packed_w <= head.y
                    or other.z >= head.z + head.packed_h or other.z + other.packed_h <= head.z
                )


class FakeRunner:
    def __init__(self):
        self.built = []
        self.runs = []

    def build_problem_request_for_trip(self, trip_id, objective, time_limit_sec, db):
        self.built.append((trip_id, objective, time_limit_sec))
        return problem([])

    async def run(self, job_uuid, problem=None, db=None):
        self.runs.append((job_uuid, problem))


class FakeJobService:
    def __init__(self):
        self.created = []

    def create_job(self, trip_id, config, db):
        self.created.append(trip_id)

        class Job:
            job_uuid = "job-1"
        return Job()


class TestSubmitJobWithPins:
    def setup_method(self):
        self.runner = FakeRunner()
        self.jobs = FakeJobService()
        app.dependency_overrides[get_db] = lambda: None
        app.dependency_overrides[require_optimization_service] = lambda: {}
        app.dependency_overrides[get_async_runner] = lambda: self.runner
        app.dependency_overrides[get_optimization_job_service] = lambda: self.jobs

    def teardown_method(self):
        app.dependency_overrides.clear()

    def post(self, pins):
        return TestClient(app).post("/api/v1/optimization/jobs", json={
            "trip_id": 5, "objective": "MAX_VOLUME", "time_limit_sec": 30, "pinned_placements": pins,
        })

    def test_invalid_pins_are_rejected_before_any_job(self):
        response = self.post([
            {"package_id": 1, "pos_x": 0, "pos_y": 0, "pos_z": 0},
            {"package_id": 2, "pos_x": 50, "pos_y": 0, "pos_z": 0},
        ])

        assert response.status_code == 400
        body = response.json()
        assert body["code"] == "PINNED_SET_INVALID"
        assert body["errors"]
        assert self.jobs.created == []
        assert self.runner.runs == []

    def test_valid_pins_run_with_the_pinned_problem(self):
        response = self.post([{"package_id": 2, "pos_x": 250, "pos_y": 70, "pos_z": 0}])

        assert response.status_code == 202
        assert self.runner.built == [(5, "MAX_VOLUME", 30)]
        assert self.jobs.created == [5]
        job_uuid, pinned_problem = self.runner.runs[0]
        assert job_uuid == "job-1"
        assert [str(p.package_id) for p in pinned_problem.pinned] == ["2"]

    def test_without_pins_the_job_builds_its_own_problem(self):
        response = self.post([])

        assert response.status_code == 202
        assert self.runner.built == []
        assert self.runner.runs == [("job-1", None)]
