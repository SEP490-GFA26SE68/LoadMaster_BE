
import pytest

from app.dto.optimization.engine.problem_request import (
    ProblemRequest,
    VehicleData,
    PackageData,
)
from app.service.optimize.optimization_client import OptimizationClient


def make_problem(
        length=120,
        width=80,
        height=60,
        rotation_allowed=True,
        allowed_rotations=None,
):
    """
    Thùng xe: 100 x 150 x 100 cm.

    Kiện: 120 x 80 x 60 cm.
    - Hướng 0: 120 x 80 x 60 -> không vừa theo X.
    - Hướng 1: 80 x 120 x 60 -> vừa cả ba chiều.

    Vì vậy kiện chỉ có thể xếp nếu hướng 1 được phép.
    """
    vehicle = VehicleData(
        inner_l=100,
        inner_w=150,
        inner_h=100,
        max_payload_kg=1000,
        door_w=150,
        door_h=100,
    )

    package = PackageData(
        id="package-01",
        l=length,
        w=width,
        h=height,
        weight=20,
        stop_index=1,
        rotation_allowed=rotation_allowed,
        allowed_rotations=(
            allowed_rotations
            if allowed_rotations is not None
            else [0, 1]
        ),
    )

    return ProblemRequest(
        vehicle=vehicle,
        packages=[package],
        stops=[],
        objective="MAX_VOLUME_UTIL",
        time_limit_sec=60,
    )


def test_hc03_solver_uses_allowed_rotation():
    """Chỉ hướng 1 vừa thùng và được phép."""
    problem = make_problem(
        rotation_allowed=True,
        allowed_rotations=[0, 1],
    )

    result = OptimizationClient(
        token_provider=object()
    ).solve_in_process(problem)

    assert len(result.placements) == 1

    placement = result.placements[0]
    assert placement.rotation_type == 1
    assert placement.packed_l == 80
    assert placement.packed_w == 120
    assert placement.packed_h == 60


def test_hc03_solver_rejects_forbidden_rotation():
    """Hướng 1 vừa thùng nhưng không được phép."""
    problem = make_problem(
        rotation_allowed=True,
        allowed_rotations=[0],
    )

    result = OptimizationClient(
        token_provider=object()
    ).solve_in_process(problem)

    assert len(result.placements) == 0
    assert len(result.unplaced) == 1


def test_hc03_solver_respects_global_rotation_lock():
    """Khóa xoay tổng phải ưu tiên hơn danh sách."""
    problem = make_problem(
        rotation_allowed=False,
        allowed_rotations=[0, 1],
    )

    result = OptimizationClient(
        token_provider=object()
    ).solve_in_process(problem)

    assert len(result.placements) == 0
    assert len(result.unplaced) == 1


def test_hc03_solver_does_not_invent_rotation():
    """Solver không được tự dùng hướng 1 khi danh sách rỗng."""
    problem = make_problem(
        rotation_allowed=True,
        allowed_rotations=[],
    )

    result = OptimizationClient(
        token_provider=object()
    ).solve_in_process(problem)

    assert len(result.placements) == 0
    assert len(result.unplaced) == 1


def test_hc03_solver_keeps_original_orientation():
    """Kiện vừa ở hướng 0 và bị khóa xoay vẫn phải xếp được."""
    problem = make_problem(
        length=80,
        width=60,
        height=50,
        rotation_allowed=False,
        allowed_rotations=[0],
    )

    result = OptimizationClient(
        token_provider=object()
    ).solve_in_process(problem)

    assert len(result.placements) == 1

    placement = result.placements[0]
    assert placement.rotation_type == 0
    assert (
               placement.packed_l,
               placement.packed_w,
               placement.packed_h,
           ) == (80, 60, 50)
