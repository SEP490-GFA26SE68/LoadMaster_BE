
import pytest

from app.dto.optimization.engine.problem_request import (
    VehicleData,
    PackageData,
)
from app.dto.optimization.engine.engine_response import PlacementData
from app.service.optimize.constraint_engine import ConstraintEngine


def make_vehicle(payload=3500):
    return VehicleData(
        inner_l=450,
        inner_w=200,
        inner_h=200,
        max_payload_kg=payload,
        door_w=185,
        door_h=190,
    )


def make_package(package_id, weight):
    return PackageData(
        id=package_id,
        l=100,
        w=80,
        h=60,
        weight=weight,
        rotation_allowed=True,
        allowed_rotations=[0],
    )


def make_placement(package_id, x=0):
    return PlacementData(
        package_id=package_id,
        x=x,
        y=0,
        z=0,
        packed_l=100,
        packed_w=80,
        packed_h=60,
        rotation_type=0,
        step_sequence=1,
    )


@pytest.mark.parametrize(
    "weight,expected_pass",
    [
        (1000, True),
        (3000, True),
        (3500, True),
        (3500.01, False),
        (4000, False),
    ],
)
def test_hc04_single_package(weight, expected_pass):
    vehicle = make_vehicle()
    placement = make_placement("package-A")

    packages_map = {
        "package-A": make_package("package-A", weight),
    }

    result = ConstraintEngine.check_payload(
        placement,
        [],
        vehicle,
        packages_map,
    )

    assert result.passed is expected_pass

    if not expected_pass:
        assert result.violation_code == "PAYLOAD_EXCEEDED"


@pytest.mark.parametrize(
    "existing_weights,new_weight,expected_pass",
    [
        ([1000, 1000], 1000, True),
        ([1000, 1500], 1000, True),
        ([1000, 1500], 1000.01, False),
        ([2000, 1000], 1000, False),
        ([500, 500, 500], 500, True),
    ],
)
def test_hc04_multiple_packages(
        existing_weights,
        new_weight,
        expected_pass,
):
    vehicle = make_vehicle()

    existing = []
    packages_map = {}

    for idx, weight in enumerate(existing_weights):
        package_id = f"existing-{idx}"

        existing.append(
            make_placement(package_id, x=idx * 110)
        )
        packages_map[package_id] = make_package(
            package_id,
            weight,
        )

    candidate = make_placement("candidate")
    packages_map["candidate"] = make_package(
        "candidate",
        new_weight,
    )

    result = ConstraintEngine.check_payload(
        candidate,
        existing,
        vehicle,
        packages_map,
    )

    assert result.passed is expected_pass

    if not expected_pass:
        assert result.violation_code == "PAYLOAD_EXCEEDED"


@pytest.mark.parametrize(
    "payload",
    [
        0,
        -1,
        float("nan"),
        float("inf"),
    ],
)
def test_hc04_invalid_vehicle_payload(payload):
    vehicle = make_vehicle(payload)
    placement = make_placement("package-A")

    packages_map = {
        "package-A": make_package("package-A", 1000),
    }

    result = ConstraintEngine.check_payload(
        placement,
        [],
        vehicle,
        packages_map,
    )

    assert result.passed is False
    assert result.violation_code == "INVALID_PAYLOAD_DATA"


@pytest.mark.parametrize(
    "weight",
    [
        0,
        -1,
        float("nan"),
        float("inf"),
    ],
)
def test_hc04_invalid_package_weight(weight):
    vehicle = make_vehicle()
    placement = make_placement("package-A")

    packages_map = {
        "package-A": make_package("package-A", weight),
    }

    result = ConstraintEngine.check_payload(
        placement,
        [],
        vehicle,
        packages_map,
    )

    assert result.passed is False
    assert result.violation_code == "INVALID_PAYLOAD_DATA"


def test_hc04_missing_package_metadata():
    vehicle = make_vehicle()
    placement = make_placement("missing-package")

    result = ConstraintEngine.check_payload(
        placement,
        [],
        vehicle,
        packages_map={},
    )

    assert result.passed is False
    assert result.violation_code == "INVALID_PAYLOAD_DATA"


def test_hc04_called_from_check_all():
    """HC-04 phải được gọi từ ConstraintEngine.check_all."""
    vehicle = make_vehicle(payload=3500)

    existing = [
        make_placement("package-A", x=0),
    ]
    candidate = make_placement("package-B", x=120)

    packages_map = {
        "package-A": make_package("package-A", 2000),
        "package-B": make_package("package-B", 2000),
    }

    violations = ConstraintEngine.check_all(
        candidate,
        context={
            "vehicle": vehicle,
            "existing_placements": existing,
            "packages_map": packages_map,
        },
    )

    assert any(
        v.violation_code == "PAYLOAD_EXCEEDED"
        for v in violations
    )


def test_hc04_exact_payload_from_check_all():
    """Tổng khối lượng đúng 3500 kg phải hợp lệ."""
    vehicle = make_vehicle(payload=3500)

    existing = [
        make_placement("package-A", x=0),
    ]
    candidate = make_placement("package-B", x=120)

    packages_map = {
        "package-A": make_package("package-A", 2000),
        "package-B": make_package("package-B", 1500),
    }

    violations = ConstraintEngine.check_all(
        candidate,
        context={
            "vehicle": vehicle,
            "existing_placements": existing,
            "packages_map": packages_map,
        },
    )

    assert not any(
        v.violation_code == "PAYLOAD_EXCEEDED"
        for v in violations
    )
