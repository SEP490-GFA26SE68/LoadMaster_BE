
import pytest

from app.dto.optimization.engine.problem_request import (
    PackageData,
    VehicleData,
)
from app.dto.optimization.engine.engine_response import PlacementData
from app.service.optimize.constraint_engine import ConstraintEngine


def make_package(
        allowed_rotations=None,
        rotation_allowed=True,
) -> PackageData:
    return PackageData(
        id="package-01",
        l=100,
        w=80,
        h=60,
        weight=20,
        allowed_rotations=(
            allowed_rotations
            if allowed_rotations is not None
            else []
        ),
        rotation_allowed=rotation_allowed,
    )


def make_placement(rotation_type=0) -> PlacementData:
    return PlacementData(
        package_id="package-01",
        x=0,
        y=0,
        z=0,
        packed_l=100,
        packed_w=80,
        packed_h=60,
        rotation_type=rotation_type,
        step_sequence=1,
    )


@pytest.mark.parametrize(
    "rotation_type,allowed_rotations,expected_pass",
    [
        # Hướng 0 được phép
        (0, [0, 1], True),

        # Hướng 1 được phép
        (1, [0, 1], True),

        # Hướng không thuộc danh sách
        (2, [0, 1], False),

        # Chỉ được phép hướng 0
        (1, [0], False),

        # Chỉ được phép hướng 1
        (1, [1], True),

        # Danh sách chứa mã dạng chuỗi
        (1, ["0", "1"], True),

        # Hướng không thuộc danh sách chuỗi
        (2, ["0", "1"], False),

        # Danh sách rỗng: tương thích hành vi hiện tại
        (0, [], True),
        (1, [], True),
    ],
)
def test_hc03_allowed_rotations(
        rotation_type,
        allowed_rotations,
        expected_pass,
):
    package = make_package(
        allowed_rotations=allowed_rotations,
    )
    placement = make_placement(rotation_type)

    result = ConstraintEngine.check_rotation_allowed(
        package,
        placement,
    )

    assert result.passed is expected_pass

    if not expected_pass:
        assert (
                result.violation_code
                == "ORIENTATION_NOT_ALLOWED"
        )


@pytest.mark.parametrize(
    "rotation_type,expected_pass",
    [
        (0, True),
        (1, False),
        (2, False),
    ],
)
def test_hc03_rotation_locked(
        rotation_type,
        expected_pass,
):
    package = make_package(
        allowed_rotations=[],
        rotation_allowed=False,
    )
    placement = make_placement(rotation_type)

    result = ConstraintEngine.check_rotation_allowed(
        package,
        placement,
    )

    assert result.passed is expected_pass

    if not expected_pass:
        assert result.violation_code == "ORIENTATION_NOT_ALLOWED"


def test_hc03_locked_package_rejects_even_if_list_allows():
    """rotation_allowed=False luôn cấm hướng khác 0."""
    package = make_package(
        allowed_rotations=[0, 1],
        rotation_allowed=False,
    )
    placement = make_placement(rotation_type=1)

    result = ConstraintEngine.check_rotation_allowed(
        package,
        placement,
    )

    assert result.passed is False
    assert result.violation_code == "ORIENTATION_NOT_ALLOWED"


def test_hc03_unrotated_rejected_if_not_in_allowed_list():
    """Danh sách cụ thể không chứa hướng 0 thì phải từ chối."""
    package = make_package(
        allowed_rotations=[1],
        rotation_allowed=True,
    )
    placement = make_placement(rotation_type=0)

    result = ConstraintEngine.check_rotation_allowed(
        package,
        placement,
    )

    assert result.passed is False
    assert result.violation_code == "ORIENTATION_NOT_ALLOWED"


def test_hc03_requires_placement():
    """Không có placement thì không thể kiểm tra hướng xoay."""
    package = make_package(allowed_rotations=[0, 1])

    result = ConstraintEngine.check_rotation_allowed(
        package,
        None,
    )

    assert result.passed is False
    assert result.violation_code == "ORIENTATION_NOT_ALLOWED"


def test_hc03_called_from_check_all():
    """CORE phải gọi HC-03 thông qua check_all."""
    package = make_package(
        allowed_rotations=[0],
    )
    placement = make_placement(rotation_type=1)

    vehicle = VehicleData(
        inner_l=450,
        inner_w=200,
        inner_h=200,
        max_payload_kg=3500,
        door_w=185,
        door_h=190,
    )

    violations = ConstraintEngine.check_all(
        placement,
        context={
            "vehicle": vehicle,
            "existing_placements": [],
            "packages_map": {
                str(package.id): package,
            },
        },
    )

    assert any(
        v.violation_code == "ORIENTATION_NOT_ALLOWED"
        for v in violations
    )
