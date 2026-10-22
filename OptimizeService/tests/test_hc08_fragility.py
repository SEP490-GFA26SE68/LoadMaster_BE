
import pytest

from app.dto.optimization.engine.problem_request import (
    PackageData,
)
from app.dto.optimization.engine.engine_response import (
    PlacementData,
)
from app.service.optimize.constraint_engine import ConstraintEngine


def make_package(package_id, fragile=False, weight=20):
    return PackageData(
        id=package_id,
        l=100,
        w=100,
        h=50,
        weight=weight,
        fragile=fragile,
        rotation_allowed=False,
        allowed_rotations=[0],
        max_stack_weight_kg=200,
    )


def make_placement(
        package_id,
        x=0,
        y=0,
        z=0,
        l=100,
        w=100,
        h=50,
):
    return PlacementData(
        package_id=package_id,
        x=x,
        y=y,
        z=z,
        packed_l=l,
        packed_w=w,
        packed_h=h,
        rotation_type=0,
        step_sequence=1,
    )


def check_fragility(candidate, existing, packages):
    return ConstraintEngine.check_fragility(
        candidate,
        existing,
        packages,
    )


def assert_fragility(candidate, existing, packages, expected_pass):
    result = check_fragility(candidate, existing, packages)

    assert result.passed is expected_pass

    if not expected_pass:
        assert result.violation_code == "FRAGILITY_VIOLATION"


@pytest.mark.parametrize(
    "bottom_fragile,top_fragile,expected_pass",
    [
        (False, False, True),
        (False, True, True),
        (True, False, False),
        (True, True, False),
    ],
)
def test_hc08_direct_stacking(
        bottom_fragile,
        top_fragile,
        expected_pass,
):
    """Kiểm tra bốn tổ hợp fragile khi xếp hai tầng."""
    bottom = make_placement("A", z=0)
    top = make_placement("B", z=50)

    packages = {
        "A": make_package("A", fragile=bottom_fragile),
        "B": make_package("B", fragile=top_fragile),
    }

    assert_fragility(
        top,
        [bottom],
        packages,
        expected_pass,
    )


def test_hc08_three_layers_fragile_bottom():
    """Kiện dễ vỡ ở tầng đáy không được chịu tải gián tiếp."""
    bottom = make_placement("A", z=0)
    middle = make_placement("B", z=50)
    top = make_placement("C", z=100)

    packages = {
        "A": make_package("A", fragile=True),
        "B": make_package("B"),
        "C": make_package("C"),
    }

    assert_fragility(
        top,
        [bottom, middle],
        packages,
        False,
    )


def test_hc08_three_layers_fragile_middle():
    """Kiện dễ vỡ ở tầng giữa không được chịu tải từ tầng trên."""
    bottom = make_placement("A", z=0)
    middle = make_placement("B", z=50)
    top = make_placement("C", z=100)

    packages = {
        "A": make_package("A"),
        "B": make_package("B", fragile=True),
        "C": make_package("C"),
    }

    assert_fragility(
        top,
        [bottom, middle],
        packages,
        False,
    )


def test_hc08_three_layers_fragile_top():
    """Kiện dễ vỡ ở trên cùng được phép nếu không chịu tải."""
    bottom = make_placement("A", z=0)
    middle = make_placement("B", z=50)
    top = make_placement("C", z=100)

    packages = {
        "A": make_package("A"),
        "B": make_package("B"),
        "C": make_package("C", fragile=True),
    }

    assert_fragility(
        top,
        [bottom, middle],
        packages,
        True,
    )


def test_hc08_fragile_packages_side_by_side():
    """Hai kiện dễ vỡ nằm cạnh nhau không truyền tải cho nhau."""
    existing = make_placement("A", x=0, z=0)
    candidate = make_placement("B", x=100, z=0)

    packages = {
        "A": make_package("A", fragile=True),
        "B": make_package("B", fragile=True),
    }

    assert_fragility(
        candidate,
        [existing],
        packages,
        True,
    )


def test_hc08_package_above_but_not_touching():
    """Kiện nằm cao hơn nhưng không tiếp xúc không tạo tải chồng."""
    bottom = make_placement("A", z=0, h=50)
    top = make_placement("B", z=70)

    packages = {
        "A": make_package("A", fragile=True),
        "B": make_package("B"),
    }

    # HC-08 chỉ xét quan hệ truyền tải.
    # HC-05 và HC-06 sẽ từ chối kiện bị treo.
    assert_fragility(top, [bottom], packages, True)


def test_hc08_package_touching_only_edge():
    """Tiếp xúc cạnh nhưng không có diện tích đỡ."""
    bottom = make_placement("A", x=0, z=0)
    top = make_placement("B", x=100, z=50)

    packages = {
        "A": make_package("A", fragile=True),
        "B": make_package("B"),
    }

    assert_fragility(top, [bottom], packages, True)


def test_hc08_two_supports_one_fragile():
    """Một trong hai kiện đỡ dễ vỡ thì HC-08 phải FAIL."""
    bottom_a = make_placement("A", x=0, l=50)
    bottom_b = make_placement("B", x=50, l=50)
    top = make_placement("C", z=50)

    packages = {
        "A": make_package("A", fragile=True),
        "B": make_package("B"),
        "C": make_package("C"),
    }

    assert_fragility(
        top,
        [bottom_a, bottom_b],
        packages,
        False,
    )


def test_hc08_two_supports_none_fragile():
    """Hai kiện đỡ đều không dễ vỡ thì HC-08 PASS."""
    bottom_a = make_placement("A", x=0, l=50)
    bottom_b = make_placement("B", x=50, l=50)
    top = make_placement("C", z=50)

    packages = {
        "A": make_package("A"),
        "B": make_package("B"),
        "C": make_package("C"),
    }

    assert_fragility(
        top,
        [bottom_a, bottom_b],
        packages,
        True,
    )


def test_hc08_fragile_package_on_floor():
    """Kiện dễ vỡ nằm trên sàn và không chịu tải."""
    candidate = make_placement("A", z=0)

    packages = {
        "A": make_package("A", fragile=True),
    }

    assert_fragility(candidate, [], packages, True)


def test_hc08_missing_package_metadata():
    """Không được âm thầm bỏ qua metadata bị thiếu."""
    candidate = make_placement("missing")

    assert_fragility(
        candidate,
        [],
        {},
        False,
    )


@pytest.mark.parametrize(
    "invalid_value",
    [
        float("nan"),
        float("inf"),
        float("-inf"),
    ],
)
def test_hc08_invalid_geometry(invalid_value):
    candidate = make_placement(
        "A",
        x=invalid_value,
    )

    packages = {
        "A": make_package("A"),
    }

    assert_fragility(
        candidate,
        [],
        packages,
        False,
    )


def test_hc08_called_from_check_all():
    """HC-08 phải được gọi thông qua ConstraintEngine.check_all."""
    bottom = make_placement("A", z=0)
    top = make_placement("B", z=50)

    packages = {
        "A": make_package("A", fragile=True),
        "B": make_package("B"),
    }

    violations = ConstraintEngine.check_all(
        top,
        context={
            "vehicle": None,
            "existing_placements": [bottom],
            "packages_map": packages,
        },
    )

    assert any(
        v.violation_code == "FRAGILITY_VIOLATION"
        for v in violations
    )
