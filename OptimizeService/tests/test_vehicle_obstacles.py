"""Fixed obstacles are vehicle geometry, never cargo. All solver tests run offline."""
import json
import time
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest
from pydantic import ValidationError
from sqlalchemy import create_engine
from sqlalchemy.exc import OperationalError
from sqlalchemy.orm import Session

from app.dto.optimization.engine.problem_request import ObstacleData, ProblemRequest, VehicleData
from app.dto.optimization.engine.engine_response import PlacementData, EngineOptimizationResponse
from app.service.optimize.async_optimization_runner import AsyncOptimizationRunner
from app.service.optimize.constraint_engine import ConstraintEngine
from app.service.optimize.load_balance_search import LoadBalanceSearch, validate_plan
from app.service.optimize.optimization_client import OptimizationClient
from app.service.optimize.unload_feasibility_validator import UnloadFeasibilityValidator
from tests.test_load_balance_search import vehicle, package, problem, placement, assert_safe


def obstacle(**overrides):
    data = dict(id=1, name="Wheel well", type="WHEEL_ARCH", x=360, y=0, z=0,
                length=100, width=35, height=45, load_bearing=False)
    data.update(overrides)
    return ObstacleData(**data)


def real_case():
    data = json.loads((Path(__file__).parent / "fixtures/load_plan_47_obstacles.json").read_text(encoding="utf-8"))
    request = ProblemRequest.model_validate(data["problem"])
    packed = [PlacementData(package_id=str(p["package_id"]), x=p["pos_x"], y=p["pos_y"], z=p["pos_z"],
                            packed_l=p["packed_length"], packed_w=p["packed_width"], packed_h=p["packed_height"],
                            rotation_type=p["rotation_type"]) for p in data["persisted_placements"]]
    return request, packed


def test_plan_47_package_47_is_rejected_with_real_wheel_well():
    request, packed = real_case()
    p = next(p for p in packed if p.package_id == "47")
    result = ConstraintEngine.check_obstacles(p, request.vehicle)
    assert not result.passed
    assert result.violation_code == "OBSTACLE_OVERLAP"
    assert "47" in result.detail and "obstacle 1" in result.detail
    o = request.vehicle.obstacles[0]
    spans = [min(a + size, b + other) - max(a, b) for a, size, b, other in
             zip((p.x, p.y, p.z), (p.packed_l, p.packed_w, p.packed_h),
                 (o.x, o.y, o.z), (o.length, o.width, o.height))]
    assert spans == [50, 20, 25]
    metadata = {str(p.id): p for p in request.packages}
    assert "OBSTACLE_OVERLAP" in validate_plan(packed, request.vehicle, metadata, time.monotonic() + 5)


def test_all_real_eighteen_placements_against_both_wheel_wells():
    request, packed = real_case()
    collisions = [(p.package_id, o.id) for p in packed for o in request.vehicle.obstacles
                  if ConstraintEngine.obstacle_overlaps_bounds(o, (p.x, p.y, p.z),
                      (p.x + p.packed_l, p.y + p.packed_w, p.z + p.packed_h))]
    assert len(packed) == 18 and len(request.vehicle.obstacles) == 2
    assert collisions == [("47", 1)]


@pytest.mark.parametrize("inward", [0, 1e-8, 0.1])
def test_side_contact_is_allowed_but_any_positive_penetration_is_rejected(inward):
    p = package("A", l=50, w=35, h=25)
    result = ConstraintEngine.check_obstacles(placement(p, 365, 35 - inward), vehicle(obstacles=[obstacle()]))
    assert result.passed == (inward == 0)


@pytest.mark.parametrize("bearing", [False, True])
def test_obstacle_support_and_stacking_respect_load_bearing(bearing):
    p = package("A", l=50, w=30, h=25)
    o = obstacle(load_bearing=bearing)
    v = vehicle(obstacles=[o])
    top = placement(p, 365, 0, 45)
    codes = [r.violation_code for r in ConstraintEngine.check_all(top,
             {"vehicle": v, "packages_map": {p.id: p}, "existing_placements": []})]
    if bearing:
        assert codes == []
        assert ConstraintEngine.check_stacking(top, [], {p.id: p}, obstacles=[o]).passed
        assert UnloadFeasibilityValidator.validate([top], {p.id: p}, v).passed
    else:
        assert "NON_BEARING_SUPPORT" in codes
        assert "INSUFFICIENT_SUPPORT_AREA" in codes


def test_load_bearing_foundation_does_not_disable_cargo_stack_limits():
    base = package("base", l=50, w=30, h=25, max_stack_weight_kg=0)
    upper = package("upper", l=50, w=30, h=25)
    v = vehicle(obstacles=[obstacle(load_bearing=True)])
    boxes = [placement(base, 365, 0, 45), placement(upper, 365, 0, 70)]
    metadata = {p.id: p for p in (base, upper)}
    assert "STACKING_WEIGHT_EXCEEDED" in validate_plan(boxes, v, metadata, time.monotonic() + 5)


def test_collision_uses_rotated_dimensions():
    p = package("rotated", l=20, w=60, h=25, rotation_allowed=True, allowed_rotations=[0, 1])
    v = vehicle(obstacles=[obstacle()])
    original = placement(p, 330, 0)
    rotated = original.model_copy(update={"packed_l": 60, "packed_w": 20, "rotation_type": 1})
    assert ConstraintEngine.check_obstacles(original, v).passed
    assert not ConstraintEngine.check_obstacles(rotated, v).passed
    codes = [r.violation_code for r in ConstraintEngine.check_all(rotated,
             {"vehicle": v, "packages_map": {p.id: p}, "existing_placements": []})]
    assert "OBSTACLE_OVERLAP" in codes and "ORIENTATION_NOT_ALLOWED" not in codes


def test_initial_packing_and_final_guard_never_accept_obstacle_overlap(monkeypatch):
    request = problem([package("A"), package("B")], vehicle=vehicle(max_cog_offset_ratio=0.25, obstacles=[obstacle(
        x=280, y=90, length=70, width=70, height=50)]))
    captured = []
    def keep_initial(self, initial, zones):
        captured.extend(initial)
        return initial
    monkeypatch.setattr(LoadBalanceSearch, "improve", keep_initial)
    result = OptimizationClient().solve_in_process(request)
    assert captured and result.placements
    assert all(ConstraintEngine.check_obstacles(p, request.vehicle).passed for p in captured)
    assert_safe(result, request)


def test_rigid_translation_into_obstacle_is_rejected_then_repack_is_safe(monkeypatch):
    request = problem([package("A")], vehicle=vehicle(obstacles=[obstacle(
        x=280, y=100, length=60, width=50, height=50)]))
    initial = [placement(request.packages[0], 550, 105)]
    search = LoadBalanceSearch(request, time.monotonic() + 5)
    observed = []
    consider = search._consider
    def record(trial):
        valid = consider(trial)
        observed.extend(search.last_violations)
        return valid
    monkeypatch.setattr(search, "_consider", record)
    result = search.improve(initial, {})
    assert "OBSTACLE_OVERLAP" in observed
    assert len(result) == 1
    assert validate_plan(result, request.vehicle, search.packages, time.monotonic() + 5) == []


@pytest.mark.parametrize("motion", ["pull", "lateral", "lift"])
def test_swept_unload_path_cannot_cross_fixed_obstacles(motion):
    p = package("A")
    start_y = 0 if motion == "lateral" else 105
    box = placement(p, 200, start_y)
    wall = obstacle(x=400, y=0, length=20, width=240, height=250)
    obstacles = [wall]
    if motion == "lateral":
        obstacles = [obstacle(x=195, y=50, length=50, width=20, height=250)]
    elif motion == "lift":
        obstacles = [wall.model_copy(update={"height": 100}),
                     obstacle(id=2, x=195, y=0, z=30, length=50, width=240, height=20)]
    v = vehicle(obstacles=obstacles, door_w=30 if motion == "lateral" else 210)
    assert ConstraintEngine.check_obstacles(box, v).passed  # Endpoints do not intersect.
    assert UnloadFeasibilityValidator.validate([box], {p.id: p}, v.model_copy(update={"obstacles": []})).passed
    result = UnloadFeasibilityValidator.validate([box], {p.id: p}, v)
    assert not result.passed
    assert result.violation_code == "MULTI_DROP_LIFO_VIOLATION"


def test_unload_can_lift_over_low_obstacle_without_penetrating_it():
    p = package("A")
    v = vehicle(obstacles=[obstacle(x=400, y=0, length=20, width=240, height=30)])
    assert UnloadFeasibilityValidator.validate([placement(p, 200, 105)], {p.id: p}, v).passed


def test_obstacle_stays_after_first_delivery_stop_is_removed():
    early, late = package("early", 1), package("late", 2)
    boxes = [placement(early, 500, 105), placement(late, 200, 105)]
    metadata = {p.id: p for p in (early, late)}
    v = vehicle(obstacles=[obstacle(x=400, y=0, length=20, width=240, height=250)])
    assert UnloadFeasibilityValidator.validate([boxes[0]], metadata, v).passed
    assert not UnloadFeasibilityValidator.validate(boxes, metadata, v).passed
    without = UnloadFeasibilityValidator.validate(boxes, metadata, v.model_copy(update={"obstacles": []}))
    assert without.passed and without.unload_sequence == ["early", "late"]


def test_real_request_offline_solver_preserves_ids_and_all_hard_constraints():
    request, _ = real_case()
    request.time_limit_sec = 5
    result = OptimizationClient().solve_in_process(request)
    assert len(result.placements) == 18  # Verified feasible with both real wheel wells, not forced by the solver.
    assert_safe(result, request)
    for p in result.placements:
        assert ConstraintEngine.check_obstacles(p, request.vehicle).passed


@pytest.mark.parametrize("bad", [dict(length=0), dict(x=float("nan")), dict(load_bearing=None)])
def test_invalid_obstacle_metadata_is_not_silently_ignored(bad):
    with pytest.raises(ValidationError):
        obstacle(**bad)


def test_duplicate_and_out_of_bounds_obstacles_are_rejected():
    with pytest.raises(ValidationError, match="unique"):
        vehicle(obstacles=[obstacle(), obstacle()])
    with pytest.raises(ValidationError, match="bounds"):
        vehicle(obstacles=[obstacle(x=600)])


def test_timeout_fallback_keeps_only_obstacle_free_validated_incumbent():
    p = package("A", l=50, w=35, h=25)
    request = problem([p], vehicle=vehicle(obstacles=[obstacle()]))
    search = LoadBalanceSearch(request, time.monotonic() + 5)
    safe = [placement(p, 310, 105)]
    assert search._consider(safe)
    unsafe = [placement(p, 365, 15)]
    assert not search._consider(unsafe)
    assert "OBSTACLE_OVERLAP" in search.last_violations
    search.deadline = time.monotonic() - 1
    kept = search.improve(unsafe, {})
    assert kept == safe
    assert validate_plan(kept, request.vehicle, search.packages, time.monotonic() + 5) == []


@pytest.mark.asyncio
async def test_remote_engine_cannot_ignore_wheel_wells(monkeypatch):
    request, packed = real_case()
    client = OptimizationClient(engine_url="https://example.invalid")
    monkeypatch.setattr(client, "_solve_http", AsyncMock(return_value=EngineOptimizationResponse(placements=packed)))
    with pytest.raises(ValueError, match="OBSTACLE_OVERLAP"):
        await client.solve(request)


@pytest.mark.asyncio
async def test_remote_safe_result_is_preserved(monkeypatch):
    p = package("A")
    request = problem([p], vehicle=vehicle(obstacles=[obstacle()]))
    expected = EngineOptimizationResponse(placements=[placement(p, 290, 105)])
    client = OptimizationClient(engine_url="https://example.invalid")
    monkeypatch.setattr(client, "_solve_http", AsyncMock(return_value=expected))
    assert await client.solve(request) is expected


@pytest.mark.asyncio
async def test_remote_result_cannot_lose_package_ids(monkeypatch):
    request = problem([package("A"), package("B")], vehicle=vehicle(obstacles=[obstacle()]))
    client = OptimizationClient(engine_url="https://example.invalid")
    monkeypatch.setattr(client, "_solve_http", AsyncMock(return_value=EngineOptimizationResponse()))
    with pytest.raises(ValueError, match="invalid package partition"):
        await client.solve(request)


def test_unreadable_obstacle_relation_is_a_clear_error():
    class Broken:
        id = 1
        @property
        def obstacles(self):
            raise OperationalError("SELECT obstacles", {}, Exception("unavailable"))
    with pytest.raises(ValueError, match="Unable to load valid fixed obstacles for vehicle type 1"):
        AsyncOptimizationRunner.load_vehicle_obstacles(Broken())
    with pytest.raises(ValueError, match="Unable to load"):
        AsyncOptimizationRunner.load_vehicle_obstacles(SimpleNamespace(id=1, obstacles=None))


def test_sqlalchemy_builder_loads_only_correct_vehicle_type_in_cm():
    from app.config.database import Base
    from app.entity.trip_model import VehicleType, VehicleTypeObstacle, Vehicle, Trip
    engine = create_engine("sqlite:///:memory:")  # Isolated test DB, never the configured shared database.
    try:
        Base.metadata.create_all(engine)
        with Session(engine) as db:
            for id in (1, 2):
                db.add(VehicleType(id=id, inner_length=620, inner_width=240, inner_height=250,
                                   max_payload_kg=5000, max_cog_offset_ratio=0.15))
                db.add(VehicleTypeObstacle(id=id, vehicle_type_id=id, name="Wheel well", type="WHEEL_ARCH",
                       x=360, y=0, z=0, length=100, width=35, height=45, load_bearing=False))
            db.add(Vehicle(id=1, vehicle_type_id=1, license_plate="TEST"))
            db.add(Trip(id=1, vehicle_id=1, trip_code="TEST"))
            db.commit()
            runner = AsyncOptimizationRunner.__new__(AsyncOptimizationRunner)
            runner.job_service = SimpleNamespace(get_job=lambda *args: SimpleNamespace(
                trip_id=1, objective="MAX_VOLUME", time_limit_sec=60))
            request = runner.build_problem_request("test-only", db)
            assert [o.id for o in request.vehicle.obstacles] == [1]
            o = request.vehicle.obstacles[0]
            assert (o.x, o.y, o.z, o.length, o.width, o.height) == (360, 0, 0, 100, 35, 45)
            assert not o.load_bearing
            assert VehicleData.model_validate(request.vehicle.model_dump()) == request.vehicle
    finally:
        engine.dispose()
