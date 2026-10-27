
import pytest

from app.service.optimize.constraint_engine import ConstraintEngine
from app.dto.optimization.engine.problem_request import VehicleData
from app.dto.optimization.engine.engine_response import PlacementData


def make_vehicle(
        door_w: float | None,
        door_h: float | None,
) -> VehicleData:
    return VehicleData(
        inner_l=450,
        inner_w=200,
        inner_h=200,
        max_payload_kg=3500,
        door_w=door_w,
        door_h=door_h,
    )


def make_placement(
        width: float,
        height: float,
) -> PlacementData:
    return PlacementData(
        package_id="test-package-1",
        x=0,
        y=0,
        z=0,
        packed_l=100,
        packed_w=width,
        packed_h=height,
        rotation_type=0,
        step_sequence=1,
    )


@pytest.mark.parametrize(
    "width,height,expected_pass",
    [
        (180, 180, True),
        (190, 180, False),
        (180, 200, False),
        (185, 190, True),
    ],
)
def test_hc10_door_dimensions(
        width: float,
        height: float,
        expected_pass: bool,
):
    vehicle = make_vehicle(
        door_w=185,
        door_h=190,
    )
    placement = make_placement(width, height)

    result = ConstraintEngine.check_door(
        placement,
        vehicle,
    )

    assert result.passed is expected_pass

    if not expected_pass:
        assert (
                result.violation_code
                == "PACKAGE_CANNOT_PASS_DOOR"
        )


@pytest.mark.parametrize(
    "door_w,door_h",
    [
        (None, 190),
        (185, None),
        (None, None),
        (0, 190),
        (185, 0),
    ],
)
def test_hc10_missing_or_invalid_door(
        door_w: float | None,
        door_h: float | None,
):
    vehicle = make_vehicle(door_w, door_h)
    placement = make_placement(100, 100)

    result = ConstraintEngine.check_door(
        placement,
        vehicle,
    )

    assert result.passed is False
    assert (
            result.violation_code
            == "PACKAGE_CANNOT_PASS_DOOR"
    )


def test_hc10_is_called_from_check_all():
    vehicle = make_vehicle(
        door_w=185,
        door_h=190,
    )
    placement = make_placement(
        width=195,
        height=180,
    )

    violations = ConstraintEngine.check_all(
        placement,
        context={
            "vehicle": vehicle,
            "existing_placements": [],
            "packages_map": {},
        },
    )

    assert any(
        violation.violation_code
        == "PACKAGE_CANNOT_PASS_DOOR"
        for violation in violations
    )
