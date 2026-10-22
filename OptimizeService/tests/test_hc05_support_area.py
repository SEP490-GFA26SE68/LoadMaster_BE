
import pytest

from app.dto.optimization.engine.engine_response import PlacementData
from app.service.optimize.constraint_engine import ConstraintEngine


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


@pytest.mark.parametrize(
    "support_length,expected_pass",
    [
        (100, True),    # 100%
        (80, True),     # 80%
        (70, True),     # 70% - vừa đúng ngưỡng
        (69.99, False), # 69.99%
        (60, False),    # 60%
        (30, False),    # 30%
    ],
)
def test_hc05_support_threshold(
        support_length,
        expected_pass,
):
    bottom = make_placement(
        "bottom",
        l=support_length,
        w=100,
    )
    top = make_placement(
        "top",
        z=50,
    )

    result = ConstraintEngine.check_support_area(
        top,
        [bottom],
    )

    assert result.passed is expected_pass

    if not expected_pass:
        assert result.violation_code == "INSUFFICIENT_SUPPORT_AREA"


def test_hc05_package_on_floor():
    """Kiện nằm trên sàn được hỗ trợ 100%."""
    placement = make_placement("top", z=0)

    result = ConstraintEngine.check_support_area(
        placement,
        [],
    )

    assert result.passed is True


def test_hc05_no_support():
    """Kiện nằm trên cao nhưng không có vật đỡ."""
    placement = make_placement("top", z=50)

    result = ConstraintEngine.check_support_area(
        placement,
        [],
    )

    assert result.passed is False
    assert result.violation_code == "INSUFFICIENT_SUPPORT_AREA"


def test_hc05_two_supports_combined():
    """Hai kiện bên dưới cùng nâng đỡ đủ 70%."""
    bottom_a = make_placement(
        "bottom-A",
        x=0,
        l=40,
    )
    bottom_b = make_placement(
        "bottom-B",
        x=40,
        l=40,
    )
    top = make_placement("top", z=50)

    result = ConstraintEngine.check_support_area(
        top,
        [bottom_a, bottom_b],
    )

    assert result.passed is True


def test_hc05_overlapping_support_regions():
    """Không tính trùng diện tích hai vùng hỗ trợ."""
    bottom_a = make_placement(
        "bottom-A",
        x=0,
        l=40,
    )
    bottom_b = make_placement(
        "bottom-B",
        x=20,
        l=40,
    )
    top = make_placement("top", z=50)

    # Diện tích hợp thực tế: 60%.
    # Cách cộng cũ: 40% + 40% = 80%.
    result = ConstraintEngine.check_support_area(
        top,
        [bottom_a, bottom_b],
    )

    assert result.passed is False
    assert result.violation_code == "INSUFFICIENT_SUPPORT_AREA"


def test_hc05_support_not_touching():
    """Kiện không chạm trực tiếp đáy thì không hỗ trợ."""
    bottom = make_placement(
        "bottom",
        z=0,
        h=40,
    )
    top = make_placement(
        "top",
        z=50,
    )

    result = ConstraintEngine.check_support_area(
        top,
        [bottom],
    )

    assert result.passed is False


def test_hc05_support_outside_base():
    """Chỉ tính phần diện tích nằm dưới đáy kiện."""
    bottom = make_placement(
        "bottom",
        x=-50,
        l=100,
    )
    top = make_placement(
        "top",
        z=50,
    )

    # Vùng giao: 50 x 100 = 50%.
    result = ConstraintEngine.check_support_area(
        top,
        [bottom],
    )

    assert result.passed is False


@pytest.mark.parametrize(
    "invalid_value",
    [
        float("nan"),
        float("inf"),
        float("-inf"),
    ],
)
def test_hc05_invalid_geometry(invalid_value):
    top = make_placement(
        "top",
        z=50,
        l=invalid_value,
    )

    result = ConstraintEngine.check_support_area(
        top,
        [],
    )

    assert result.passed is False
    assert result.violation_code == "INSUFFICIENT_SUPPORT_AREA"


def test_hc05_invalid_base():
    """Diện tích đáy bằng 0 không được PASS."""
    top = make_placement(
        "top",
        l=0,
        z=0,
    )

    result = ConstraintEngine.check_support_area(
        top,
        [],
    )

    assert result.passed is False


def test_hc05_negative_z():
    """Kiện có tọa độ Z âm không được coi là nằm trên sàn."""
    top = make_placement(
        "top",
        z=-1,
    )

    result = ConstraintEngine.check_support_area(
        top,
        [],
    )

    assert result.passed is False


def test_hc05_called_from_check_all():
    """HC-05 phải được gọi từ check_all."""
    top = make_placement(
        "top",
        z=50,
    )

    violations = ConstraintEngine.check_all(
        top,
        context={
            "vehicle": None,
            "existing_placements": [],
            "packages_map": {},
        },
    )

    assert any(
        v.violation_code == "INSUFFICIENT_SUPPORT_AREA"
        for v in violations
    )
