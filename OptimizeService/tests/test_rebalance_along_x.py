from app.dto.optimization.engine.engine_response import PlacementData
from app.dto.optimization.engine.problem_request import PackageData, VehicleData
from app.service.optimize.optimization_client import OptimizationClient

VEHICLE = VehicleData(
    inner_l=600, inner_w=200, inner_h=200, max_payload_kg=5000,
    door_w=200, door_h=200,
    front_axle_x=-100, rear_axle_x=450,
    front_axle_limit_kg=6000, rear_axle_limit_kg=10000,
    empty_front_axle_load_kg=2000, empty_rear_axle_load_kg=2000,
)


def _package(package_id, stop_index):
    return PackageData(id=package_id, l=50, w=50, h=50, weight=100, stop_index=stop_index, allowed_rotations=[0])


def _at(package_id, x, y=0.0, z=0.0, pinned=False):
    return PlacementData(package_id=package_id, x=x, y=y, z=z, packed_l=50, packed_w=50, packed_h=50, pinned=pinned)


def _cog_x(placements, packages_map):
    total = sum(packages_map[str(p.package_id)].weight for p in placements)
    return sum(packages_map[str(p.package_id)].weight * (p.x + p.packed_l / 2) for p in placements) / total


def test_blocks_close_up_in_lifo_order_and_center_the_load():
    """Điểm 2 (giao sau) sát vách trước, điểm 1 ra sát cửa: hàng dồn về phía cửa, trọng tâm lệch."""
    packages_map = {"deep": _package("deep", 2), "door-a": _package("door-a", 1), "door-b": _package("door-b", 1)}
    placements = [_at("deep", 0), _at("door-a", 500), _at("door-b", 550)]

    moved = OptimizationClient._rebalance_along_x(placements, packages_map, VEHICLE)

    x_of = {str(p.package_id): p.x for p in moved}
    assert x_of["deep"] < x_of["door-a"] < x_of["door-b"]
    assert x_of["door-a"] == x_of["deep"] + 50
    assert abs(_cog_x(moved, packages_map) - 300) < 1e-6
    assert [(p.y, p.z) for p in moved] == [(p.y, p.z) for p in placements]


def test_pinned_cargo_is_never_moved():
    packages_map = {"deep": _package("deep", 2), "door": _package("door", 1)}
    placements = [_at("deep", 0, pinned=True), _at("door", 550)]

    assert OptimizationClient._rebalance_along_x(placements, packages_map, VEHICLE) is None


def test_interleaved_stop_blocks_are_left_alone():
    """Khối của hai điểm giao xen nhau theo X: dời nguyên khối sẽ chồng kiện, nên không dời."""
    packages_map = {"deep-a": _package("deep-a", 2), "deep-b": _package("deep-b", 2), "door": _package("door", 1)}
    placements = [_at("deep-a", 0), _at("door", 40, y=60), _at("deep-b", 300)]

    assert OptimizationClient._rebalance_along_x(placements, packages_map, VEHICLE) is None
