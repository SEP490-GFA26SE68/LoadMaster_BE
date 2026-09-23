"""
Tests for TripValidationService — S3-01

Seams under test:
  TripValidationService.validate(trip, vehicle_type, packages) -> ValidationResponse

Strategy:
  - Không mock DB; inject dữ liệu trực tiếp qua dataclass/simple objects
  - Test behavior qua public interface, không test internals
  - Mock chỉ tại seam DB (dùng simple in-memory objects thay vì Session thật)
"""
import pytest
from dataclasses import dataclass, field
from typing import Optional
from uuid import uuid4

from app.service.optimize.trip_validation_service import TripValidationService, TripData, VehicleTypeData, PackageData


# ---------------------------------------------------------------------------
# Test fixtures — đơn giản, không cần DB
# ---------------------------------------------------------------------------

def make_vehicle_type(
    inner_l: float = 10.0,
    inner_w: float = 2.5,
    inner_h: float = 2.5,
    max_payload_kg: float = 1000.0,
) -> VehicleTypeData:
    return VehicleTypeData(
        inner_l=inner_l,
        inner_w=inner_w,
        inner_h=inner_h,
        max_payload_kg=max_payload_kg,
    )


def make_package(
    length: float = 1.0,
    width: float = 1.0,
    height: float = 1.0,
    weight: float = 10.0,
) -> PackageData:
    return PackageData(
        id=uuid4(),
        length=length,
        width=width,
        height=height,
        weight=weight,
    )


def make_trip(vehicle_type: Optional[VehicleTypeData] = None, packages: Optional[list] = None) -> TripData:
    return TripData(
        id=uuid4(),
        vehicle_type=vehicle_type,
        packages=packages if packages is not None else [],
    )


# ---------------------------------------------------------------------------
# Khởi tạo service
# ---------------------------------------------------------------------------
service = TripValidationService()


# ---------------------------------------------------------------------------
# S3-01 AC1: Kiểm tra vehicle đã gán cho trip?
# ---------------------------------------------------------------------------
class TestVehicleAssignment:
    def test_no_vehicle_returns_error(self):
        """Trip không có vehicle → can_optimize=False, errors chứa thông báo vehicle"""
        trip = make_trip(vehicle_type=None, packages=[make_package()])
        result = service.validate(trip)
        assert result.can_optimize is False
        assert any("vehicle" in e.lower() for e in result.errors)

    def test_trip_with_vehicle_passes_vehicle_check(self):
        """Trip có vehicle → không có lỗi về vehicle"""
        trip = make_trip(vehicle_type=make_vehicle_type(), packages=[make_package()])
        result = service.validate(trip)
        assert not any("vehicle" in e.lower() for e in result.errors)


# ---------------------------------------------------------------------------
# S3-01 AC2: Kiểm tra ≥ 1 package trong trip?
# ---------------------------------------------------------------------------
class TestPackageCount:
    def test_no_packages_returns_error(self):
        """Trip không có package → can_optimize=False"""
        trip = make_trip(vehicle_type=make_vehicle_type(), packages=[])
        result = service.validate(trip)
        assert result.can_optimize is False
        assert any("package" in e.lower() for e in result.errors)

    def test_one_package_passes_count_check(self):
        """Trip có ≥1 package → không có lỗi về package count"""
        trip = make_trip(vehicle_type=make_vehicle_type(), packages=[make_package()])
        result = service.validate(trip)
        assert not any("package" in e.lower() for e in result.errors)


# ---------------------------------------------------------------------------
# S3-01 AC3: tổng weight ≤ max_payload_kg
# ---------------------------------------------------------------------------
class TestWeightLimit:
    def test_total_weight_exceeds_payload_returns_error(self):
        """Tổng weight > max_payload_kg → can_optimize=False"""
        vt = make_vehicle_type(max_payload_kg=50.0)
        packages = [make_package(weight=30.0), make_package(weight=30.0)]  # 60 > 50
        trip = make_trip(vehicle_type=vt, packages=packages)
        result = service.validate(trip)
        assert result.can_optimize is False
        assert any("weight" in e.lower() or "payload" in e.lower() for e in result.errors)

    def test_total_weight_equal_payload_passes(self):
        """Tổng weight == max_payload_kg → OK"""
        vt = make_vehicle_type(max_payload_kg=50.0)
        packages = [make_package(weight=25.0), make_package(weight=25.0)]
        trip = make_trip(vehicle_type=vt, packages=packages)
        result = service.validate(trip)
        assert not any("weight" in e.lower() or "payload" in e.lower() for e in result.errors)

    def test_total_weight_under_payload_passes(self):
        """Tổng weight < max_payload_kg → OK"""
        vt = make_vehicle_type(max_payload_kg=100.0)
        packages = [make_package(weight=10.0)]
        trip = make_trip(vehicle_type=vt, packages=packages)
        result = service.validate(trip)
        assert result.can_optimize is True


# ---------------------------------------------------------------------------
# S3-01 AC4: tổng volume ≤ inner_l × inner_w × inner_h
# ---------------------------------------------------------------------------
class TestVolumeLimit:
    def test_total_volume_exceeds_capacity_returns_error(self):
        """Tổng volume packages > volume xe → can_optimize=False"""
        vt = make_vehicle_type(inner_l=2.0, inner_w=1.0, inner_h=1.0)  # capacity=2m³
        # 3 package 1x1x1 = 3m³ > 2m³
        packages = [make_package(length=1.0, width=1.0, height=1.0) for _ in range(3)]
        trip = make_trip(vehicle_type=vt, packages=packages)
        result = service.validate(trip)
        assert result.can_optimize is False
        assert any("volume" in e.lower() for e in result.errors)

    def test_total_volume_fits_passes(self):
        """Tổng volume < capacity → OK"""
        vt = make_vehicle_type(inner_l=5.0, inner_w=2.0, inner_h=2.0)  # 20m³
        packages = [make_package(length=1.0, width=1.0, height=1.0)]   # 1m³
        trip = make_trip(vehicle_type=vt, packages=packages)
        result = service.validate(trip)
        assert not any("volume" in e.lower() for e in result.errors)


# ---------------------------------------------------------------------------
# S3-01 AC5: mỗi package fit ≥ 1 rotation
# ---------------------------------------------------------------------------
class TestPackageFit:
    def test_package_too_large_for_any_rotation_returns_error(self):
        """Package lớn hơn xe ở mọi rotation → can_optimize=False"""
        vt = make_vehicle_type(inner_l=1.0, inner_w=1.0, inner_h=1.0)
        # Package 2×2×2 — lớn hơn mọi chiều xe
        packages = [make_package(length=2.0, width=2.0, height=2.0, weight=1.0)]
        trip = make_trip(vehicle_type=vt, packages=packages)
        result = service.validate(trip)
        assert result.can_optimize is False
        assert any("fit" in e.lower() or "rotation" in e.lower() or "size" in e.lower() for e in result.errors)

    def test_package_fits_in_one_rotation_passes(self):
        """Package fit xe ở ít nhất 1 rotation → không có lỗi fit"""
        vt = make_vehicle_type(inner_l=3.0, inner_w=1.0, inner_h=1.0)
        # Package 0.5×0.5×0.5 — fit mọi rotation
        packages = [make_package(length=0.5, width=0.5, height=0.5, weight=1.0)]
        trip = make_trip(vehicle_type=vt, packages=packages)
        result = service.validate(trip)
        assert not any("fit" in e.lower() or "rotation" in e.lower() for e in result.errors)


# ---------------------------------------------------------------------------
# S3-01 AC6: ValidationResponse shape
# ---------------------------------------------------------------------------
class TestValidationResponseShape:
    def test_valid_trip_returns_can_optimize_true_with_empty_errors(self):
        """Trip hợp lệ → can_optimize=True, errors=[]"""
        vt = make_vehicle_type()
        packages = [make_package()]
        trip = make_trip(vehicle_type=vt, packages=packages)
        result = service.validate(trip)
        assert result.can_optimize is True
        assert result.errors == []

    def test_result_has_required_fields(self):
        """ValidationResponse phải có đủ 3 fields"""
        trip = make_trip(vehicle_type=make_vehicle_type(), packages=[make_package()])
        result = service.validate(trip)
        assert hasattr(result, "can_optimize")
        assert hasattr(result, "warnings")
        assert hasattr(result, "errors")
        assert isinstance(result.warnings, list)
        assert isinstance(result.errors, list)

    def test_only_warnings_still_can_optimize(self):
        """Chỉ có warnings (không có errors) → can_optimize=True"""
        vt = make_vehicle_type(max_payload_kg=100.0)
        # Package nhỏ hơn 50% capacity → warning về low utilization (nếu implement)
        packages = [make_package(weight=1.0, length=0.1, width=0.1, height=0.1)]
        trip = make_trip(vehicle_type=vt, packages=packages)
        result = service.validate(trip)
        # Kể cả có warnings, vẫn có thể optimize
        assert result.can_optimize is True
