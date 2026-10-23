
import pytest

from app.service.optimize.constraint_engine import ConstraintEngine
from app.dto.optimization.engine.problem_request import (
    VehicleData,
    PackageData,
)
from app.dto.optimization.engine.engine_response import PlacementData


def make_vehicle(ratio=0.15):
    return VehicleData(
        inner_l=600,
        inner_w=240,
        inner_h=250,
        max_payload_kg=10000,
        max_cog_offset_ratio=ratio,
    )


def make_package(package_id, weight=100):
    return PackageData(
        id=package_id,
        l=100,
        w=80,
        h=60,
        weight=weight,
    )


def make_placement(package_id, x, y, z=0):
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
    return ConstraintEngine.check_cog(
        all_placements=placements,
        vehicle=vehicle or make_vehicle(),
        packages_map={
            str(package.id): package
            for package in packages
        },
    )


# TEST 01: Trọng tâm ở chính giữa thùng xe.
def test_hc11_pass_centered():
    result = check(
        placements=[
            make_placement("P1", x=250, y=80),
        ],
        packages=[
            make_package("P1"),
        ],
    )

    assert result.passed is True


# TEST 02: Lệch X nhưng vẫn nằm trong vùng 15%.
def test_hc11_pass_within_x_limit():
    result = check(
        placements=[
            make_placement("P1", x=340, y=80),
        ],
        packages=[
            make_package("P1"),
        ],
    )

    # COG_X = 390; tâm X = 300.
    # Độ lệch = 90 / 600 = 15%.
    assert result.passed is True


# TEST 03: Trọng tâm vượt giới hạn X.
def test_hc11_fail_x_offset():
    result = check(
        placements=[
            make_placement("P1", x=450, y=80),
        ],
        packages=[
            make_package("P1"),
        ],
    )

    assert result.passed is False
    assert result.violation_code == "CENTER_OF_GRAVITY_OUT_OF_RANGE"


# TEST 04: Trọng tâm vượt giới hạn Y.
def test_hc11_fail_y_offset():
    result = check(
        placements=[
            make_placement("P1", x=250, y=160),
        ],
        packages=[
            make_package("P1"),
        ],
    )

    assert result.passed is False
    assert result.violation_code == "CENTER_OF_GRAVITY_OUT_OF_RANGE"


# TEST 05: Trọng tâm phải tính theo khối lượng.
def test_hc11_mass_weighted_center():
    result = check(
        placements=[
            make_placement("P1", x=100, y=80),
            make_placement("P2", x=400, y=80),
        ],
        packages=[
            make_package("P1", weight=100),
            make_package("P2", weight=300),
        ],
    )

    # COG_X = (100*150 + 300*450) / 400 = 375.
    # Độ lệch = 75 / 600 = 12.5%.
    assert result.passed is True


# TEST 06: Không tìm được package.
def test_hc11_missing_package():
    result = check(
        placements=[
            make_placement("UNKNOWN", x=250, y=80),
        ],
        packages=[],
    )

    assert result.passed is False
    assert result.violation_code == "COG_PACKAGE_DATA_MISSING"


# TEST 07: Khối lượng kiện bằng 0.
def test_hc11_invalid_weight():
    result = check(
        placements=[
            make_placement("P1", x=250, y=80),
        ],
        packages=[
            make_package("P1", weight=0),
        ],
    )

    assert result.passed is False
    assert result.violation_code == "COG_INVALID_WEIGHT"


# TEST 08: Không có placements.
def test_hc11_empty_placements():
    result = check(
        placements=[],
        packages=[],
    )

    assert result.passed is True
