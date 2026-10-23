
import pytest

from app.dto.optimization.engine.problem_request import PackageData
from app.dto.optimization.engine.engine_response import PlacementData
from app.service.optimize.constraint_engine import ConstraintEngine


def make_package(package_id, weight, stack_limit=None):
    return PackageData(
        id=package_id,
        l=100,
        w=100,
        h=50,
        weight=weight,
        rotation_allowed=False,
        allowed_rotations=[0],
        max_stack_weight_kg=stack_limit,
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


def check_stack(candidate, existing, packages):
    return ConstraintEngine.check_stacking(
        candidate,
        existing,
        packages,
    )


@pytest.mark.parametrize(
    "top_weight,limit,expected_pass",
    [
        (40, 60, True),
        (60, 60, True),
        (70, 60, False),
        (100, 60, False),
    ],
)
def test_hc07_single_layer(top_weight, limit, expected_pass):
    """Một kiện đặt trực tiếp lên một kiện khác."""
    bottom = make_placement("A", z=0)
    top = make_placement("B", z=50)

    packages = {
        "A": make_package("A", 20, stack_limit=limit),
        "B": make_package("B", top_weight),
    }

    result = check_stack(top, [bottom], packages)

    assert result.passed is expected_pass

    if not expected_pass:
        assert result.violation_code == "STACKING_WEIGHT_EXCEEDED"


@pytest.mark.parametrize(
    "top_weight,middle_weight,bottom_limit,expected_pass",
    [
        (20, 30, 60, True),
        (30, 30, 60, True),
        (40, 30, 60, False),
        (50, 30, 60, False),
    ],
)
def test_hc07_multiple_layers(
        top_weight,
        middle_weight,
        bottom_limit,
        expected_pass,
):
    """
    Ba tầng C -> B -> A.

    A phải chịu trọng lượng của cả B và C.
    """
    bottom = make_placement("A", z=0)
    middle = make_placement("B", z=50)
    top = make_placement("C", z=100)

    packages = {
        "A": make_package("A", 20, stack_limit=bottom_limit),
        "B": make_package("B", middle_weight, stack_limit=100),
        "C": make_package("C", top_weight),
    }

    result = check_stack(
        top,
        [bottom, middle],
        packages,
    )

    assert result.passed is expected_pass

    if not expected_pass:
        assert result.violation_code == "STACKING_WEIGHT_EXCEEDED"


def test_hc07_split_load_across_two_supports():
    """
    Kiện C nặng 100kg đặt trên A và B.

    A và B hỗ trợ mỗi bên 50% diện tích.
    Mỗi kiện nhận 50kg.
    """
    bottom_a = make_placement(
        "A", x=0, l=50, z=0
    )
    bottom_b = make_placement(
        "B", x=50, l=50, z=0
    )
    top = make_placement(
        "C", x=0, l=100, z=50
    )

    packages = {
        "A": make_package("A", 20, stack_limit=60),
        "B": make_package("B", 20, stack_limit=60),
        "C": make_package("C", 100),
    }

    result = check_stack(
        top,
        [bottom_a, bottom_b],
        packages,
    )

    assert result.passed is True


def test_hc07_split_load_exceeds_one_support():
    """Một kiện đỡ chịu tải vượt giới hạn."""
    bottom_a = make_placement(
        "A", x=0, l=70, z=0
    )
    bottom_b = make_placement(
        "B", x=70, l=30, z=0
    )
    top = make_placement(
        "C", x=0, l=100, z=50
    )

    packages = {
        "A": make_package("A", 20, stack_limit=60),
        "B": make_package("B", 20, stack_limit=60),
        "C": make_package("C", 100),
    }

    # A nhận 70kg, vượt giới hạn 60kg.
    result = check_stack(
        top,
        [bottom_a, bottom_b],
        packages,
    )

    assert result.passed is False
    assert result.violation_code == "STACKING_WEIGHT_EXCEEDED"


def test_hc07_new_package_on_floor():
    """Kiện đặt trên sàn không truyền tải lên kiện nào khác."""
    candidate = make_placement("A", z=0)

    packages = {
        "A": make_package("A", 100, stack_limit=0),
    }

    result = check_stack(candidate, [], packages)

    assert result.passed is True


def test_hc07_missing_package_data():
    """Thiếu metadata của kiện phải bị từ chối."""
    candidate = make_placement("missing")

    result = check_stack(candidate, [], {})

    assert result.passed is False
    assert result.violation_code == "INVALID_STACKING_DATA"


@pytest.mark.parametrize(
    "weight",
    [
        0,
        -1,
        float("nan"),
        float("inf"),
    ],
)
def test_hc07_invalid_weight(weight):
    candidate = make_placement("A")

    packages = {
        "A": make_package("A", weight),
    }

    result = check_stack(candidate, [], packages)

    assert result.passed is False
    assert result.violation_code == "INVALID_STACKING_DATA"


def test_hc07_floating_package():
    """Kiện ở trên cao nhưng không có kiện nào đỡ."""
    candidate = make_placement("A", z=50)

    packages = {
        "A": make_package("A", 20),
    }

    result = check_stack(candidate, [], packages)

    assert result.passed is False
    assert result.violation_code == "INVALID_STACKING_DATA"


def test_hc07_called_from_check_all():
    """HC-07 phải được kiểm tra thông qua check_all()."""
    bottom = make_placement("A", z=0)
    top = make_placement("B", z=50)

    packages = {
        "A": make_package("A", 20, stack_limit=30),
        "B": make_package("B", 40),
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
        v.violation_code == "STACKING_WEIGHT_EXCEEDED"
        for v in violations
    )



def test_hc07_null_stack_capacity_rejects_load():
    """
    HC-07 — Phương án A:
    NULL nghĩa là chưa khai báo khả năng chịu tải.
    Không được đặt kiện khác lên trên.
    """
    bottom = make_placement("A", z=0)
    top = make_placement("B", z=50)

    packages = {
        "A": make_package("A", 20, stack_limit=None),
        "B": make_package("B", 100),
    }

    result = check_stack(top, [bottom], packages)

    assert result.passed is False
    assert result.violation_code == "STACKING_WEIGHT_EXCEEDED"
    assert "no declared stacking capacity" in result.detail


def test_hc07_null_stack_capacity_without_load():
    """
    Kiện chưa khai báo khả năng chịu tải vẫn
    được phép đặt trên sàn nếu không chịu tải
    từ bất kỳ kiện nào phía trên.
    """
    bottom = make_placement("A", z=0)

    packages = {
        "A": make_package("A", 20, stack_limit=None),
    }

    result = check_stack(bottom, [], packages)

    assert result.passed is True



def test_hc07_zero_stack_capacity_rejects_load():
    """
    max_stack_weight_kg = 0:
    Kiện không được chịu tải chồng xếp.
    """
    bottom = make_placement("A", z=0)
    top = make_placement("B", z=50)

    packages = {
        "A": make_package("A", 20, stack_limit=0),
        "B": make_package("B", 10),
    }

    result = check_stack(top, [bottom], packages)

    assert result.passed is False
    assert result.violation_code == "STACKING_WEIGHT_EXCEEDED"
