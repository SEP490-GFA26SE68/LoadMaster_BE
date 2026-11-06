from app.dto.optimization.engine.problem_request import (
    PackageData,
    ProblemRequest,
    StopData,
    VehicleData,
)
from app.service.optimize.optimization_client import OptimizationClient


def _vehicle(**overrides):
    values = dict(
        inner_l=400, inner_w=200, inner_h=200, max_payload_kg=5000,
        door_w=200, door_h=200,
        front_axle_x=-100, rear_axle_x=300,
        front_axle_limit_kg=6000, rear_axle_limit_kg=10000,
        empty_front_axle_load_kg=2000, empty_rear_axle_load_kg=2000,
    )
    values.update(overrides)
    return VehicleData(**values)


def _package(package_id, stop_index):
    return PackageData(
        id=package_id, l=50, w=50, h=50, weight=10,
        stop_index=stop_index, allowed_rotations=[0],
    )


def _solve(vehicle, packages, stops):
    problem = ProblemRequest(
        vehicle=vehicle, packages=packages, stops=stops,
        objective="MAX_VOLUME_UTIL", time_limit_sec=60,
    )
    return OptimizationClient(token_provider=object()).solve_in_process(problem)


def test_stop_zone_follows_stop_sequence_not_stop_id():
    """stop_index của kiện là thứ tự điểm. Khi id và thứ tự đảo nhau
    (điểm id 1 đi thứ hai, điểm id 2 đi thứ nhất), vùng của kiện phải theo thứ tự."""
    result = _solve(
        _vehicle(),
        [_package("first-1", 1), _package("first-2", 1), _package("second-1", 2)],
        [StopData(id=2, sequence=1), StopData(id=1, sequence=2)],
    )

    zone_of = {str(p.package_id): str(p.stop_zone_id) for p in result.placements}
    assert zone_of == {"first-1": "2", "first-2": "2", "second-1": "1"}


def test_vehicle_without_door_reports_reason_instead_of_crashing():
    result = _solve(
        _vehicle(door_w=None, door_h=None),
        [_package("a", 1)],
        [StopData(id=1, sequence=1)],
    )

    assert result.placements == []
    assert [u.violated_constraints for u in result.unplaced] == [["PACKAGE_CANNOT_PASS_DOOR"]]
