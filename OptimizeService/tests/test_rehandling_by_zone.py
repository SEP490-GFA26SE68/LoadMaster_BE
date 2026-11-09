"""Số dỡ-xếp lại: kiện có tâm theo X nằm ngoài vùng của điểm giao mình (cùng luật với StopZoneLayout của LoadMasterService)."""

from types import SimpleNamespace

from app.dto.optimization.engine.problem_request import PackageData, ProblemRequest, StopData, VehicleData
from app.dto.optimization.engine.zone_data import ZoneData
from app.service.optimize.optimization_client import OptimizationClient
from app.service.optimize.stop_zone_calculator import StopZoneCalculator


def zone(stop_id, start, end):
    return ZoneData(stop_id=stop_id, zone_start_x=start, zone_end_x=end, zone_volume=0, zone_weight_limit=0)


def box(package_id, x, length):
    return SimpleNamespace(package_id=package_id, x=x, packed_l=length)


ZONES = [zone(1, 210, 410), zone(2, 0, 200)]


def test_centre_in_the_buffer_belongs_to_the_nearer_zone_and_a_tie_goes_deeper():
    assert StopZoneCalculator.zone_at(ZONES, 203).stop_id == 2
    assert StopZoneCalculator.zone_at(ZONES, 208).stop_id == 1
    assert StopZoneCalculator.zone_at(ZONES, 205).stop_id == 2
    assert StopZoneCalculator.zone_at([], 5) is None


def test_rehandling_counts_packages_whose_centre_lies_in_another_stops_zone():
    # Kiện 11 lấn qua mép vùng nhưng tâm (195) vẫn trong vùng điểm 2 của nó; kiện 12 của điểm 1 nằm sâu trong vùng điểm 2.
    placements = [box(11, 150, 90), box(12, 0, 100), box(13, 300, 100)]

    assert StopZoneCalculator.count_rehandling(ZONES, placements, {"11": 2, "12": 1, "13": 1}) == 1


def test_solver_counts_on_its_final_layout_after_moving_the_load_for_the_centre_of_gravity():
    # Thùng 400 cm, hai điểm: vùng điểm 2 (giao sau) là [0; 334,29], vùng điểm 1 là [344,29; 400]. Xếp trong vùng thì trọng tâm
    # lệch về cửa, bộ giải dời cả hàng về vách trong: kiện của điểm 1 sang x = 300, tâm 325 nằm trong vùng điểm 2 — một lần
    # dỡ-xếp lại, như bản chỉnh tay đặt kiện ở đó sẽ được đếm.
    truck = VehicleData(
        inner_l=400, inner_w=200, inner_h=200, max_payload_kg=5000, door_w=200, door_h=200,
        front_axle_x=-100, rear_axle_x=300, front_axle_limit_kg=6000, rear_axle_limit_kg=10000,
        empty_front_axle_load_kg=2000, empty_rear_axle_load_kg=2000,
    )
    packages = [
        PackageData(id="near", l=50, w=50, h=50, weight=10, stop_index=1, allowed_rotations=[0]),
        PackageData(id="long", l=300, w=50, h=50, weight=10, stop_index=2, allowed_rotations=[0]),
    ]
    problem = ProblemRequest(vehicle=truck, packages=packages, stops=[StopData(id=1, sequence=1), StopData(id=2, sequence=2)])

    response = OptimizationClient(token_provider=object()).solve_in_process(problem)

    zones = StopZoneCalculator.calculate_zones(truck, [
        {"stop_id": 1, "sequence": 1, "packages": [{"volume": 50 * 50 * 50}]},
        {"stop_id": 2, "sequence": 2, "packages": [{"volume": 300 * 50 * 50}]},
    ]).zones
    assert sorted(str(p.package_id) for p in response.placements) == ["long", "near"]
    assert StopZoneCalculator.count_rehandling(zones, response.placements, {"near": 1, "long": 2}) == 1
    assert response.metrics.rehandling_count == 1
