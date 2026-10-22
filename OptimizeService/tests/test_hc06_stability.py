
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


def assert_stability(top, supports, expected_pass):
    result = ConstraintEngine.check_stability(top, supports)

    assert result.passed is expected_pass

    if not expected_pass:
        assert result.violation_code == "STABILITY_VIOLATION"


@pytest.mark.parametrize(
    "x,y",
    [
        (0, 0),
        (10, 10),
        (50, 50),
    ],
)
def test_hc06_fully_supported(x, y):
    """Trọng tâm nằm trong vùng nâng đỡ."""
    bottom = make_placement("bottom", l=200, w=200)
    top = make_placement("top", x=x, y=y, z=50)

    assert_stability(top, [bottom], True)


def test_hc06_center_outside_support():
    """Trọng tâm nằm ngoài vùng nâng đỡ."""
    bottom = make_placement("bottom", l=20, w=100)
    top = make_placement("top", z=50)

    assert_stability(top, [bottom], False)


def test_hc06_center_on_support_boundary():
    """Trọng tâm nằm đúng biên vùng đỡ."""
    bottom = make_placement("bottom", l=50, w=100)
    top = make_placement("top", z=50)

    assert_stability(top, [bottom], True)


def test_hc06_two_supports_across_gap():
    """
    Hai kiện nằm ở hai phía, có khoảng trống ở giữa.
    Tâm thuộc convex hull của các vùng nâng đỡ.
    """
    bottom_left = make_placement(
        "bottom-left",
        x=0,
        l=30,
    )
    bottom_right = make_placement(
        "bottom-right",
        x=70,
        l=30,
    )
    top = make_placement("top", z=50)

    assert_stability(
        top,
        [bottom_left, bottom_right],
        True,
    )


def test_hc06_two_supports_on_one_side():
    """Hai vùng đỡ đều lệch về một phía."""
    bottom_a = make_placement(
        "bottom-A",
        x=0,
        l=15,
    )
    bottom_b = make_placement(
        "bottom-B",
        x=15,
        l=15,
    )
    top = make_placement("top", z=50)

    assert_stability(top, [bottom_a, bottom_b], False)


def test_hc06_on_floor():
    """Kiện đặt trên sàn luôn có mặt đỡ."""
    top = make_placement("top", z=0)

    assert_stability(top, [], True)


def test_hc06_no_support():
    """Kiện nằm trên cao nhưng không có điểm đỡ."""
    top = make_placement("top", z=50)

    assert_stability(top, [], False)


def test_hc06_support_not_touching():
    """Mặt trên kiện dưới không chạm đáy kiện trên."""
    bottom = make_placement("bottom", h=40)
    top = make_placement("top", z=50)

    assert_stability(top, [bottom], False)


def test_hc06_invalid_dimensions():
    """Chiều dài bằng 0 không hợp lệ."""
    top = make_placement("top", l=0)

    assert_stability(top, [], False)


def test_hc06_negative_height_position():
    """Z âm không được xem là đặt trên sàn."""
    top = make_placement("top", z=-1)

    assert_stability(top, [], False)


@pytest.mark.parametrize(
    "invalid_value",
    [
        float("nan"),
        float("inf"),
        float("-inf"),
    ],
)
def test_hc06_invalid_geometry(invalid_value):
    top = make_placement(
        "top",
        z=50,
        x=invalid_value,
    )

    assert_stability(top, [], False)


def test_hc06_called_from_check_all():
    """CORE phải kiểm tra HC-06 thông qua check_all."""
    top = make_placement("top", z=50)

    violations = ConstraintEngine.check_all(
        top,
        context={
            "vehicle": None,
            "existing_placements": [],
            "packages_map": {},
        },
    )

    assert any(
        v.violation_code == "STABILITY_VIOLATION"
        for v in violations
    )


def test_hc06_independent_from_hc05():
    """
    Trọng tâm thuộc convex hull nhưng diện tích
    hỗ trợ chỉ 60%: HC-06 PASS, HC-05 FAIL.
    """
    bottom_left = make_placement(
        "bottom-left",
        x=0,
        l=30,
    )
    bottom_right = make_placement(
        "bottom-right",
        x=70,
        l=30,
    )
    top = make_placement("top", z=50)

    supports = [bottom_left, bottom_right]

    stability = ConstraintEngine.check_stability(top, supports)
    support_area = ConstraintEngine.check_support_area(top, supports)

    assert stability.passed is True
    assert support_area.passed is False
    assert support_area.violation_code == "INSUFFICIENT_SUPPORT_AREA"
