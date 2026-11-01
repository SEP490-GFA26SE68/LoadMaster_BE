
import pytest

from app.dto.optimization.engine.problem_request import (
    PackageData,
    VehicleData,
)
from app.dto.optimization.engine.engine_response import PlacementData
from app.service.optimize.unload_feasibility_validator import (
    UnloadFeasibilityValidator,
)


def make_vehicle(
        door_w=200,
        door_h=200,
):
    return VehicleData(
        inner_l=400,
        inner_w=200,
        inner_h=200,
        max_payload_kg=3500,
        door_w=door_w,
        door_h=door_h,
    )


def make_package(package_id, stop_index):
    return PackageData(
        id=package_id,
        l=50,
        w=50,
        h=50,
        weight=20,
        stop_index=stop_index,
        fragile=False,
        rotation_allowed=False,
        allowed_rotations=[0],
        max_stack_weight_kg=100,
    )


def make_placement(
        package_id,
        x=0,
        y=0,
        z=0,
        l=50,
        w=50,
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


def validate(placements, stops, vehicle=None):
    packages = {
        p.package_id: make_package(
            p.package_id,
            stops[p.package_id],
        )
        for p in placements
    }

    return UnloadFeasibilityValidator.validate(
        placements,
        packages,
        vehicle or make_vehicle(),
        )


def test_hc09_empty_plan():
    """Không có kiện nào thì trình tự dỡ rỗng hợp lệ."""
    result = validate([], {})

    assert result.passed is True
    assert result.unload_sequence == []


def test_hc09_single_package():
    """Một kiện đặt trên sàn, đúng khẩu độ cửa."""
    a = make_placement("A", x=100, y=50)

    result = validate([a], {"A": 1})

    assert result.passed is True
    assert result.unload_sequence == ["A"]


def test_hc09_correct_stop_order():
    """Stop 1 gần cửa hơn Stop 2."""
    early = make_placement("A", x=250, y=50)
    late = make_placement("B", x=100, y=50)

    result = validate(
        [late, early],
        {"A": 1, "B": 2},
    )

    assert result.passed is True
    assert result.unload_sequence == ["A", "B"]


def test_hc09_later_stop_blocks_earlier_stop():
    """Stop 2 nằm chắn giữa Stop 1 và cửa sau."""
    early = make_placement("A", x=100, y=50)
    late = make_placement("B", x=250, y=50)

    result = validate(
        [early, late],
        {"A": 1, "B": 2},
    )

    assert result.passed is False
    assert (
            result.violation_code
            == "MULTI_DROP_LIFO_VIOLATION"
    )


def test_hc09_different_lanes():
    """
    Hai kiện thuộc hai stop khác nhau,
    nhưng nằm ở hai hành lang Y không giao nhau.
    """
    early = make_placement("A", x=100, y=0)
    late = make_placement("B", x=250, y=100)

    result = validate(
        [early, late],
        {"A": 1, "B": 2},
    )

    assert result.passed is True
    assert result.unload_sequence == ["A", "B"]


def test_hc09_same_stop_unload_front_first():
    """
    Hai kiện cùng Stop 1; kiện gần cửa
    phải được dỡ trước để mở đường.
    """
    inner = make_placement("A", x=100, y=50)
    outer = make_placement("B", x=250, y=50)

    result = validate(
        [inner, outer],
        {"A": 1, "B": 1},
    )

    assert result.passed is True
    assert result.unload_sequence == ["B", "A"]


def test_hc09_narrow_door():
    """Kiện rộng hơn cửa không thể dỡ thẳng."""
    a = make_placement("A", x=100, y=50, w=100)

    result = validate(
        [a],
        {"A": 1},
        make_vehicle(door_w=80),
    )

    assert result.passed is False
    assert (
            result.violation_code
            == "MULTI_DROP_LIFO_VIOLATION"
    )


def test_hc09_outside_door_alignment():
    """
    Kiện nhỏ hơn cửa nhưng nằm lệch sang bên.
    Không được dịch ngang nên không qua cửa.
    """
    a = make_placement("A", x=100, y=0, w=50)

    result = validate(
        [a],
        {"A": 1},
        make_vehicle(door_w=100),
    )

    assert result.passed is False


def test_hc09_door_height():
    """Kiện đặt quá cao không thể đi qua cửa."""
    a = make_placement("A", x=100, y=50, z=150, h=50)

    result = validate(
        [a],
        {"A": 1},
        make_vehicle(door_h=100),
    )

    assert result.passed is False


def test_hc09_unloading_breaks_support():
    """
    Kiện A là kiện giao Stop 1, đang đỡ kiện B
    của Stop 2. Dỡ A làm B mất hỗ trợ.
    """
    bottom = make_placement("A", x=100, y=50, z=0)
    top = make_placement("B", x=100, y=50, z=50)

    result = validate(
        [bottom, top],
        {"A": 1, "B": 2},
    )

    assert result.passed is False
    assert (
            result.violation_code
            == "MULTI_DROP_LIFO_VIOLATION"
    )


def test_hc09_unloading_top_first():
    """Kiện phía trên thuộc Stop 1, dỡ trước kiện dưới Stop 2."""
    bottom = make_placement("B", x=100, y=50, z=0)
    top = make_placement("A", x=100, y=50, z=50)

    result = validate(
        [bottom, top],
        {"A": 1, "B": 2},
    )

    assert result.passed is True
    assert result.unload_sequence == ["A", "B"]


def test_hc09_three_stops():
    """Dỡ đúng ba stop theo thứ tự."""
    a = make_placement("A", x=300, y=50)
    b = make_placement("B", x=200, y=50)
    c = make_placement("C", x=100, y=50)

    result = validate(
        [c, a, b],
        {"A": 1, "B": 2, "C": 3},
    )

    assert result.passed is True
    assert result.unload_sequence == ["A", "B", "C"]


def test_hc09_duplicate_package():
    """Một kiện không được xuất hiện hai lần trong LoadPlan."""
    a1 = make_placement("A", x=100, y=50)
    a2 = make_placement("A", x=200, y=50)

    result = validate(
        [a1, a2],
        {"A": 1},
    )

    assert result.passed is False
    assert result.violation_code == "INVALID_UNLOAD_DATA"


def test_hc09_missing_stop_metadata():
    """Thiếu stop_index phải bị từ chối."""
    a = make_placement("A", x=100, y=50)

    result = UnloadFeasibilityValidator.validate(
        [a],
        packages_map={},
        vehicle=make_vehicle(),
    )

    assert result.passed is False
    assert result.violation_code == "INVALID_UNLOAD_DATA"


def test_hc09_initial_overlap():
    """LoadPlan ban đầu có hai kiện chồng lấn."""
    a = make_placement("A", x=100, y=50)
    b = make_placement("B", x=120, y=50)

    result = validate(
        [a, b],
        {"A": 1, "B": 2},
    )

    assert result.passed is False
    assert result.violation_code == "INVALID_UNLOAD_DATA"


def test_hc09_initial_floating_package():
    """LoadPlan ban đầu có kiện nằm lơ lửng."""
    a = make_placement("A", x=100, y=50, z=60)

    result = validate([a], {"A": 1})

    assert result.passed is False
    assert result.violation_code == "INVALID_UNLOAD_DATA"



@pytest.mark.parametrize(
    "invalid_stop",
    [0, -1],
)
def test_hc09_invalid_stop_sequence(invalid_stop):
    """
    Stop là số nguyên nhưng không hợp lệ.
    Validator phải từ chối.
    """
    a = make_placement("A", x=100, y=50)

    result = validate(
        [a],
        {"A": invalid_stop},
    )

    assert result.passed is False
    assert result.violation_code == "INVALID_UNLOAD_DATA"


def test_hc09_fractional_stop_rejected_by_dto():
    """
    stop_index=1.5 không hợp lệ.

    PackageData phải từ chối ngay tại tầng
    Pydantic validation, trước khi chạy HC-09.
    """
    from pydantic import ValidationError

    with pytest.raises(ValidationError):
        make_package(
            package_id="A",
            stop_index=1.5,
        )
