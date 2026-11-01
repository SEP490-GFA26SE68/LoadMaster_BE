
import pytest

from app.dto.optimization.engine.engine_response import PlacementData
from app.service.optimize.constraint_engine import ConstraintEngine


def make_placement(
        package_id: str,
        x: float = 0,
        y: float = 0,
        z: float = 0,
        l: float = 100,
        w: float = 80,
        h: float = 60,
) -> PlacementData:
    """Tạo một kiện hàng đã xếp, đơn vị cm."""
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
    "x,y,z,l,w,h,expected_pass",
    [
        # Hai kiện tách rời theo trục X
        (150, 0, 0, 50, 80, 60, True),

        # Hai kiện tách rời theo trục Y
        (0, 100, 0, 100, 50, 60, True),

        # Hai kiện tách rời theo trục Z
        (0, 0, 100, 100, 80, 40, True),

        # Chạm mặt theo X
        (100, 0, 0, 100, 80, 60, True),

        # Chạm mặt theo Y
        (0, 80, 0, 100, 80, 60, True),

        # Chạm mặt theo Z (xếp lên nhau)
        (0, 0, 60, 100, 80, 60, True),

        # Chạm cạnh
        (100, 80, 0, 100, 80, 60, True),

        # Chạm góc
        (100, 80, 60, 100, 80, 60, True),

        # Chồng lấn một phần theo X
        (90, 0, 0, 100, 80, 60, False),

        # Chồng lấn một phần theo Y
        (0, 70, 0, 100, 80, 60, False),

        # Chồng lấn một phần theo Z
        (0, 0, 50, 100, 80, 60, False),

        # Hai kiện trùng hoàn toàn
        (0, 0, 0, 100, 80, 60, False),

        # Kiện mới nằm hoàn toàn trong kiện cũ
        (10, 10, 10, 20, 20, 20, False),

        # Kiện mới bao trùm kiện cũ
        (-10, -10, -10, 150, 120, 100, False),

        # Chồng lấn đồng thời trên cả X, Y, Z
        (50, 40, 30, 100, 80, 60, False),
    ],
)
def test_hc02_non_overlap(
        x, y, z, l, w, h, expected_pass
):
    existing = make_placement("package-A")

    candidate = make_placement(
        "package-B",
        x=x,
        y=y,
        z=z,
        l=l,
        w=w,
        h=h,
    )

    result = ConstraintEngine.check_non_overlap(
        candidate,
        [existing],
    )

    assert result.passed is expected_pass

    if not expected_pass:
        assert result.violation_code == "NON_OVERLAP_VIOLATION"


def test_hc02_empty_existing_placements():
    """Kiện đầu tiên không va chạm kiện nào."""
    candidate = make_placement("package-A")

    result = ConstraintEngine.check_non_overlap(
        candidate,
        [],
    )

    assert result.passed is True


def test_hc02_multiple_existing_packages():
    """Kiểm tra va chạm với nhiều kiện đã xếp."""
    existing = [
        make_placement("package-A", x=0),
        make_placement("package-B", x=150),
        make_placement("package-C", x=300),
    ]

    candidate = make_placement(
        "package-D",
        x=170,
    )

    result = ConstraintEngine.check_non_overlap(
        candidate,
        existing,
    )

    assert result.passed is False
    assert result.violation_code == "NON_OVERLAP_VIOLATION"


@pytest.mark.parametrize(
    "invalid_value",
    [
        float("nan"),
        float("inf"),
        float("-inf"),
    ],
)
def test_hc02_invalid_geometry(invalid_value):
    existing = make_placement("package-A")

    candidate = make_placement(
        "package-B",
        x=invalid_value,
    )

    result = ConstraintEngine.check_non_overlap(
        candidate,
        [existing],
    )

    assert result.passed is False
    assert result.violation_code == "NON_OVERLAP_VIOLATION"


@pytest.mark.parametrize(
    "invalid_dimension",
    [0, -1],
)
def test_hc02_invalid_dimensions(invalid_dimension):
    existing = make_placement("package-A")

    candidate = make_placement(
        "package-B",
        l=invalid_dimension,
    )

    result = ConstraintEngine.check_non_overlap(
        candidate,
        [existing],
    )

    assert result.passed is False
    assert result.violation_code == "NON_OVERLAP_VIOLATION"


def test_hc02_called_from_check_all():
    """HC-02 phải được gọi khi CORE kiểm tra placement."""
    existing = make_placement("package-A")

    candidate = make_placement(
        "package-B",
        x=50,
    )

    violations = ConstraintEngine.check_all(
        candidate,
        context={
            "vehicle": None,
            "existing_placements": [existing],
            "packages_map": {},
        },
    )

    assert any(
        v.violation_code == "NON_OVERLAP_VIOLATION"
        for v in violations
    )
