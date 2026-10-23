
import pytest

from app.service.optimize.constraint_engine import ConstraintEngine
from app.dto.optimization.engine.problem_request import (
    VehicleData,
    PackageData,
)
from app.dto.optimization.engine.engine_response import PlacementData


def make_vehicle(**overrides):
    data = {
        "inner_l": 600,
        "inner_w": 240,
        "inner_h": 250,
        "max_payload_kg": 10000,
        "front_axle_x": 0,
        "rear_axle_x": 400,
        "front_axle_limit_kg": 5000,
        "rear_axle_limit_kg": 6000,
        "empty_front_axle_load_kg": 2000,
        "empty_rear_axle_load_kg": 2000,
    }
    data.update(overrides)
    return VehicleData(**data)


def make_package(package_id, weight=1000):
    return PackageData(
        id=package_id,
        l=100,
        w=80,
        h=60,
        weight=weight,
    )


def make_placement(package_id, x=150, y=80, z=0):
    return PlacementData(
        package_id=package_id,
        x=x,
        y=y,
        z=z,
        packed_l=100,
        packed_w=80,
        packed_h=60,
        rotation_type=0,
    )


def check(placements, packages, vehicle=None):
    return ConstraintEngine.check_axle_load(
        all_placements=placements,
        vehicle=vehicle if vehicle is not None else make_vehicle(),
        packages_map={
            str(package.id): package
            for package in packages
        },
    )


# TEST 01: Phân bố tải hợp lệ.
def test_hc12_pass_balanced_load():
    result = check(
        placements=[make_placement("P1", x=150)],
        packages=[make_package("P1", weight=2000)],
    )

    # COG_X = 200 cm.
    # Rear cargo = 2000 * 200/400 = 1000 kg.
    # Front cargo = 1000 kg.
    # Front total = 3000 kg, rear total = 3000 kg.
    assert result.passed is True


# TEST 02: Vượt giới hạn tải trục trước.
def test_hc12_fail_front_axle():
    result = check(
        placements=[make_placement("P1", x=0)],
        packages=[make_package("P1", weight=4000)],
    )

    # COG_X = 50.
    # Front cargo = 3500.
    # Front total = 5500 > 5000.
    assert result.passed is False
    assert result.violation_code == "AXLE_LOAD_EXCEEDED"
    assert "Front axle" in result.detail


# TEST 03: Vượt giới hạn tải trục sau.
def test_hc12_fail_rear_axle():
    result = check(
        placements=[make_placement("P1", x=450)],
        packages=[make_package("P1", weight=4000)],
    )

    # COG_X = 500.
    # Rear cargo = 5000.
    # Rear total = 7000 > 6000.
    assert result.passed is False
    assert result.violation_code == "AXLE_LOAD_EXCEEDED"
    assert "Rear axle" in result.detail


# TEST 04: Thiếu cấu hình vị trí trục.
def test_hc12_missing_axle_configuration():
    result = check(
        placements=[make_placement("P1")],
        packages=[make_package("P1")],
        vehicle=make_vehicle(rear_axle_x=None),
    )

    assert result.passed is False
    assert result.violation_code == "AXLE_CONFIG_MISSING"


# TEST 05: Hai trục trùng vị trí.
def test_hc12_invalid_wheelbase():
    result = check(
        placements=[make_placement("P1")],
        packages=[make_package("P1")],
        vehicle=make_vehicle(
            front_axle_x=200,
            rear_axle_x=200,
        ),
    )

    assert result.passed is False
    assert result.violation_code == "AXLE_CONFIG_INVALID"


# TEST 06: Không tìm được package.
def test_hc12_missing_package():
    result = check(
        placements=[make_placement("UNKNOWN")],
        packages=[],
    )

    assert result.passed is False
    assert result.violation_code == "AXLE_PACKAGE_DATA_MISSING"


# TEST 07: Khối lượng kiện bằng 0.
def test_hc12_invalid_package_weight():
    result = check(
        placements=[make_placement("P1")],
        packages=[make_package("P1", weight=0)],
    )

    assert result.passed is False
    assert result.violation_code == "AXLE_INVALID_WEIGHT"


# TEST 08: Tải trục xe rỗng đã vượt giới hạn.
def test_hc12_empty_vehicle_overload():
    result = check(
        placements=[],
        packages=[],
        vehicle=make_vehicle(
            empty_front_axle_load_kg=5500,
        ),
    )

    assert result.passed is False
    assert result.violation_code == "AXLE_LOAD_EXCEEDED"


# TEST 09: Tải hàng phân bố theo trọng lượng từng kiện.
def test_hc12_multiple_packages():
    result = check(
        placements=[
            make_placement("P1", x=50),
            make_placement("P2", x=350),
        ],
        packages=[
            make_package("P1", weight=1000),
            make_package("P2", weight=3000),
        ],
    )

    # P1 center X = 100, P2 center X = 400.
    # Rear cargo = (1000*100 + 3000*400) / 400
    #            = 3250 kg.
    # Front cargo = 750 kg.
    # Front total = 2750 kg.
    # Rear total = 5250 kg.
    assert result.passed is True


# TEST 10: Phản lực trục trước âm.
def test_hc12_negative_front_reaction():
    result = check(
        placements=[make_placement("P1", x=450)],
        packages=[make_package("P1", weight=2000)],
        vehicle=make_vehicle(
            empty_front_axle_load_kg=0,
            front_axle_limit_kg=5000,
            rear_axle_limit_kg=10000,
        ),
    )

    # COG_X = 500, rear axle X = 400.
    # Rear cargo = 2500, front cargo = -500.
    # Front total = -500 kg.
    assert result.passed is False
    assert result.violation_code == "AXLE_LOAD_INVALID"


# TEST 11: Không có kiện và tải xe rỗng hợp lệ.
def test_hc12_empty_load_pass():
    result = check(
        placements=[],
        packages=[],
    )

    assert result.passed is True
