
from types import SimpleNamespace

from app.dto.optimization.engine.problem_request import (
    ProblemRequest,
    VehicleData,
    PackageData,
    StopData,
)
from app.service.optimize.optimization_client import OptimizationClient
from app.service.optimize.constraint_engine import ConstraintEngine
from app.service.optimize.stop_zone_calculator import StopZoneCalculator
from app.service.optimize.unload_feasibility_validator import (
    UnloadFeasibilityValidator,
)


def make_problem(
        *,
        zone_x=250,
        cog_ratio=0.15,
        front_limit=5000,
        rear_limit=6000,
        package_weight=1000,
):
    return ProblemRequest(
        vehicle=VehicleData(
            inner_l=600,
            inner_w=240,
            inner_h=250,
            door_w=240,
            door_h=250,
            max_payload_kg=10000,
            max_cog_offset_ratio=cog_ratio,
            front_axle_x=0,
            rear_axle_x=400,
            front_axle_limit_kg=front_limit,
            rear_axle_limit_kg=rear_limit,
            empty_front_axle_load_kg=2000,
            empty_rear_axle_load_kg=2000,
        ),
        packages=[
            PackageData(
                id="P1",
                l=100,
                w=240,
                h=60,
                weight=package_weight,
                allowed_rotations=[0],
                stop_index=0,
            )
        ],
        stops=[],
    )


def run_solver(monkeypatch, problem, zone_x):
    """
    Kiểm thử luồng final validation.

    Cố định stop zone và bỏ qua các HC khác
    để chỉ kiểm tra tích hợp HC-11/HC-12.
    """

    fake_zone = SimpleNamespace(
        stop_id="default-stop",
        zone_start_x=float(zone_x),
        zone_end_x=600.0,
    )

    monkeypatch.setattr(
        StopZoneCalculator,
        "calculate_zones",
        lambda *args, **kwargs: SimpleNamespace(
            zones=[fake_zone]
        ),
    )

    # Các HC-01..HC-10 đã kiểm tra riêng.
    # Test này tập trung vào final HC-11/HC-12.
    monkeypatch.setattr(
        ConstraintEngine,
        "check_all",
        lambda *args, **kwargs: [],
    )

    monkeypatch.setattr(
        UnloadFeasibilityValidator,
        "validate",
        lambda *args, **kwargs: SimpleNamespace(
            passed=True,
            violation_code="",
        ),
    )

    client = OptimizationClient(
        engine_url=None,
    )

    return client.solve_in_process(problem)


def test_solver_accepts_valid_hc11_hc12(monkeypatch):
    """
    COG = (300, 120, 30), nằm chính giữa X/Y.

    Trục trước:
      2000 + 1000 * (400-300)/400 = 2250 kg

    Trục sau:
      2000 + 1000 * 300/400 = 2750 kg

    Cả hai không vượt giới hạn.
    """

    problem = make_problem()

    result = run_solver(
        monkeypatch,
        problem,
        zone_x=250,
    )

    assert len(result.placements) == 1
    assert len(result.unplaced) == 0

    assert result.metrics.cog_x == 300
    assert result.metrics.cog_y == 120

    assert result.metrics.front_axle_load == 2250
    assert result.metrics.rear_axle_load == 2750


def test_solver_rejects_hc11_violation(monkeypatch):
    """
    Xếp kiện sát đầu thùng:
    COG_X = 50 cm.

    Tâm thùng X = 300 cm.
    Độ lệch = 250 / 600 > 15%.

    Solver phải từ chối phương án.
    """

    problem = make_problem()

    result = run_solver(
        monkeypatch,
        problem,
        zone_x=500,
    )

    assert len(result.placements) == 0
    assert len(result.unplaced) == 1

    assert (
            "CENTER_OF_GRAVITY_OUT_OF_RANGE"
            in result.unplaced[0].violated_constraints
    )

    assert result.metrics.packed_count == 0


def test_solver_rejects_hc12_violation(monkeypatch):
    """
    COG hợp lệ ở giữa thùng.

    Rear axle load = 2750 kg.
    Rear axle limit = 2500 kg.

    Solver phải từ chối phương án.
    """

    problem = make_problem(
        rear_limit=2500,
    )

    result = run_solver(
        monkeypatch,
        problem,
        zone_x=250,
    )

    assert len(result.placements) == 0
    assert len(result.unplaced) == 1

    assert (
            "AXLE_LOAD_EXCEEDED"
            in result.unplaced[0].violated_constraints
    )

    assert result.metrics.packed_count == 0

def test_solver_multistop_imbalanced_weight_hc09_hc11_hc12():
    """
    Regression:
    - 2 DeliveryStops.
    - Stop 1: 2 kiện nhẹ, giao trước.
    - Stop 2: 3 kiện nặng, giao sau.
    - Kiểm tra HC-09, HC-11 và HC-12 cùng lúc.
    """

    problem = ProblemRequest(
        vehicle=VehicleData(
            inner_l=620,
            inner_w=240,
            inner_h=250,
            door_w=210,
            door_h=210,
            max_payload_kg=5000,
            max_cog_offset_ratio=0.15,
            front_axle_x=100,
            rear_axle_x=350,
            front_axle_limit_kg=2200,
            rear_axle_limit_kg=3300,
            empty_front_axle_load_kg=1500,
            empty_rear_axle_load_kg=1800,
        ),
        packages=[
            PackageData(
                id="44", l=40, w=30, h=25,
                weight=500,
                rotation_allowed=False,
                allowed_rotations=[0],
                stop_index=2,
            ),
            PackageData(
                id="45", l=45, w=35, h=30,
                weight=5,
                rotation_allowed=False,
                allowed_rotations=[0],
                stop_index=1,
            ),
            PackageData(
                id="46", l=35, w=25, h=20,
                weight=500,
                rotation_allowed=False,
                allowed_rotations=[0],
                stop_index=2,
            ),
            PackageData(
                id="47", l=50, w=35, h=25,
                weight=5,
                rotation_allowed=False,
                allowed_rotations=[0],
                stop_index=1,
            ),
            PackageData(
                id="48", l=40, w=30, h=30,
                weight=500,
                rotation_allowed=False,
                allowed_rotations=[0],
                stop_index=2,
            ),
        ],
        stops=[
            StopData(id=101, sequence=1),
            StopData(id=102, sequence=2),
        ],
    )

    result = OptimizationClient().solve_in_process(problem)

    # Solver phải xếp đủ 5 kiện.
    assert len(result.placements) == 5
    assert len(result.unplaced) == 0
    assert result.metrics.packed_count == 5

    # HC-11: COG nằm trong vùng ±15% tâm thùng.
    assert result.metrics.cog_x is not None
    assert result.metrics.cog_y is not None

    assert 217 <= result.metrics.cog_x <= 403
    assert 84 <= result.metrics.cog_y <= 156

    # HC-12: Không vượt tải hai trục.
    assert result.metrics.front_axle_load is not None
    assert result.metrics.rear_axle_load is not None

    assert result.metrics.front_axle_load <= 2200
    assert result.metrics.rear_axle_load <= 3300

    # HC-09: Kiểm tra khả năng dỡ hàng thực tế.
    packages_map = {
        str(pkg.id): pkg
        for pkg in problem.packages
    }

    validation = UnloadFeasibilityValidator.validate(
        placements=result.placements,
        packages_map=packages_map,
        vehicle=problem.vehicle,
    )

    assert validation.passed, (
        f"HC-09 failed: "
        f"{validation.violation_code} — {validation.detail}"
    )

    assert len(validation.unload_sequence) == 5

    stop_by_package = {
        str(pkg.id): pkg.stop_index
        for pkg in problem.packages
    }

    unload_stops = [
        stop_by_package[package_id]
        for package_id in validation.unload_sequence
    ]

    assert unload_stops == [1, 1, 2, 2, 2]

    # Không cần rehandling.
    assert result.metrics.rehandling_count == 0
