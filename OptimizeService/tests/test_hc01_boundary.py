
import pytest

from app.dto.optimization.engine.problem_request import VehicleData
from app.dto.optimization.engine.engine_response import PlacementData
from app.service.optimize.constraint_engine import ConstraintEngine


def make_vehicle() -> VehicleData:
    return VehicleData(
        inner_l=450,
        inner_w=200,
        inner_h=200,
        max_payload_kg=3500,
        door_w=185,
        door_h=190,
    )


def make_placement(
        x: float = 0,
        y: float = 0,
        z: float = 0,
        l: float = 100,
        w: float = 80,
        h: float = 60,
) -> PlacementData:
    return PlacementData(
        package_id="test-package-1",
        x=x,
        y=y,
        z=z,
        packed_l=l,
        packed_w=w,
        packed_h=h,
        rotation_type=0,
        step_sequence=1,
    )


@pytest.mark.parametrize(
    "x,y,z,l,w,h,expected_pass",
    [
        # Kiện nằm trong thùng
        (0, 0, 0, 100, 80, 60, True),

        # Kiện vừa khít cả ba biên
        (0, 0, 0, 450, 200, 200, True),

        # Kiện chạm biên phía cuối
        (350, 120, 140, 100, 80, 60, True),

        # Tọa độ âm
        (-1, 0, 0, 100, 80, 60, False),
        (0, -1, 0, 100, 80, 60, False),
        (0, 0, -1, 100, 80, 60, False),

        # Vượt kích thước thùng
        (351, 0, 0, 100, 80, 60, False),
        (0, 121, 0, 100, 80, 60, False),
        (0, 0, 141, 100, 80, 60, False),

        # Kích thước kiện không hợp lệ
        (0, 0, 0, 0, 80, 60, False),
        (0, 0, 0, 100, -1, 60, False),
        (0, 0, 0, 100, 80, 0, False),
    ],
)
def test_hc01_boundary(
        x, y, z, l, w, h, expected_pass
):
    vehicle = make_vehicle()
    placement = make_placement(x, y, z, l, w, h)

    result = ConstraintEngine.check_boundary(
        placement,
        vehicle,
    )

    assert result.passed is expected_pass

    if not expected_pass:
        assert result.violation_code == "BOUNDARY_VIOLATION"


def test_hc01_rejects_nan():
    vehicle = make_vehicle()
    placement = make_placement(x=float("nan"))

    result = ConstraintEngine.check_boundary(
        placement,
        vehicle,
    )

    assert result.passed is False
    assert result.violation_code == "BOUNDARY_VIOLATION"


def test_hc01_rejects_infinity():
    vehicle = make_vehicle()
    placement = make_placement(l=float("inf"))

    result = ConstraintEngine.check_boundary(
        placement,
        vehicle,
    )

    assert result.passed is False
    assert result.violation_code == "BOUNDARY_VIOLATION"


def test_hc01_is_called_from_check_all():
    vehicle = make_vehicle()
    placement = make_placement(x=400, l=100)

    violations = ConstraintEngine.check_all(
        placement,
        context={
            "vehicle": vehicle,
            "existing_placements": [],
            "packages_map": {},
        },
    )

    assert any(
        v.violation_code == "BOUNDARY_VIOLATION"
        for v in violations
    )
