"""
TripValidationService — S3-01 & S3-02

Validate trip trước khi chạy optimization.
Acceptance Criteria (FR-OPT-01):
  1. Vehicle đã gán cho trip?
  2. ≥ 1 package trong trip?
  3. Tổng weight ≤ vehicle_type.max_payload_kg?
  4. Tổng volume ≤ inner_l × inner_w × inner_h?
  5. Mỗi package fit ≥ 1 rotation cho phép?
  6. Warnings nếu weight > 90% max_payload_kg
"""
from __future__ import annotations

from dataclasses import dataclass, field
from itertools import permutations
from typing import Optional, Any

from app.dto.response.validation_response import ValidationResponse
from app.exception.app_exception import AppException
from app.exception.error_code import ErrorCode


# ---------------------------------------------------------------------------
# Data transfer objects (input) — decoupled khỏi ORM layer
# ---------------------------------------------------------------------------

@dataclass
class VehicleTypeData:
    """Thông số thùng xe."""
    inner_l: float  # chiều dài trong (m), converted from schema mm
    inner_w: float  # chiều rộng trong (m), converted from schema mm
    inner_h: float  # chiều cao trong (m), converted from schema mm
    max_payload_kg: float


@dataclass
class PackageData:
    """Thông số một kiện hàng."""
    id: int | Any
    length: float
    width: float
    height: float
    weight: float


@dataclass
class TripData:
    """Dữ liệu trip cần validate."""
    id: int | Any
    vehicle_type: Optional[VehicleTypeData]
    packages: list[PackageData] = field(default_factory=list)


# ---------------------------------------------------------------------------
# Service
# ---------------------------------------------------------------------------

class TripValidationService:
    """
    Validate trip trước khi submit optimization job.
    """

    WEIGHT_WARNING_THRESHOLD = 0.90

    def validate_trip_by_id(self, trip_id: int, db: Any) -> ValidationResponse:
        """
        Validate trip bằng cách load từ database qua Session SQLAlchemy.
        """
        from app.entity.trip_model import DeliveryStop, Order, Package, Trip

        if db is None:
            raise AppException(ErrorCode.TRIP_NOT_FOUND)

        trip = db.query(Trip).filter(Trip.id == trip_id).first()
        if not trip:
            raise AppException(ErrorCode.TRIP_NOT_FOUND)

        # Vehicle type
        vehicle_type_data = None
        if trip.vehicle and trip.vehicle.vehicle_type:
            vt = trip.vehicle.vehicle_type
            inner_l = float(vt.inner_length or 0) / 1000.0
            inner_w = float(vt.inner_width or 0) / 1000.0
            inner_h = float(vt.inner_height or 0) / 1000.0
            payload = float(vt.max_payload_kg or 0)
            vehicle_type_data = VehicleTypeData(
                inner_l=inner_l,
                inner_w=inner_w,
                inner_h=inner_h,
                max_payload_kg=payload,
            )

        # Packages via DeliveryStop -> Order -> Package
        packages_query = (
            db.query(Package)
            .join(Order, Package.order_id == Order.id)
            .join(DeliveryStop, Order.delivery_stop_id == DeliveryStop.id)
            .filter(DeliveryStop.trip_id == trip_id)
            .all()
        )

        packages_data: list[PackageData] = []
        for pkg in packages_query:
            w = float(pkg.actual_weight_kg or 0)
            if pkg.package_type:
                pt = pkg.package_type
                l = float(pkg.actual_length or 0) / 1000.0
                width = float(pt.width or 0) / 1000.0
                h = float(pt.height or 0) / 1000.0
            else:
                l, width, h = 0.0, 0.0, 0.0

            packages_data.append(
                PackageData(
                    id=pkg.id,
                    length=l,
                    width=width,
                    height=h,
                    weight=w,
                )
            )

        trip_data = TripData(
            id=trip_id,
            vehicle_type=vehicle_type_data,
            packages=packages_data,
        )

        return self.validate(trip_data)

    def validate(self, trip: TripData) -> ValidationResponse:
        errors: list[str] = []
        warnings: list[str] = []

        # AC1 — vehicle assigned?
        if trip.vehicle_type is None:
            errors.append("Trip chưa được gán vehicle: không thể tính toán thùng xe")
            return ValidationResponse(can_optimize=False, warnings=warnings, errors=errors)

        vt = trip.vehicle_type

        # AC2 — ≥ 1 package?
        if not trip.packages:
            errors.append("Trip không có package nào: cần ít nhất 1 kiện hàng để optimize")

        if errors:
            return ValidationResponse(can_optimize=False, warnings=warnings, errors=errors)

        # AC3 — tổng weight ≤ max_payload_kg
        total_weight = sum(p.weight for p in trip.packages)
        if vt.max_payload_kg > 0 and total_weight > vt.max_payload_kg:
            errors.append(
                f"Tổng weight {total_weight:.1f} kg vượt quá payload tối đa "
                f"{vt.max_payload_kg:.1f} kg của xe"
            )

        # AC4 — tổng volume ≤ thùng xe
        vehicle_volume = vt.inner_l * vt.inner_w * vt.inner_h
        total_volume = sum(p.length * p.width * p.height for p in trip.packages)
        if vehicle_volume > 0 and total_volume > vehicle_volume:
            errors.append(
                f"Tổng volume {total_volume:.3f} m³ vượt quá sức chứa xe "
                f"{vehicle_volume:.3f} m³ (L={vt.inner_l}×W={vt.inner_w}×H={vt.inner_h})"
            )

        # AC5 — mỗi package fit ≥ 1 rotation
        for pkg in trip.packages:
            if not self._fits_any_rotation(pkg, vt):
                errors.append(
                    f"Package {pkg.id}: kích thước ({pkg.length}×{pkg.width}×{pkg.height}) "
                    f"không fit vào xe ({vt.inner_l}×{vt.inner_w}×{vt.inner_h}) "
                    f"ở bất kỳ rotation nào"
                )

        # Warning — weight > 90% capacity
        if vt.max_payload_kg > 0 and not errors:
            ratio = total_weight / vt.max_payload_kg
            if ratio > self.WEIGHT_WARNING_THRESHOLD:
                warnings.append(
                    f"Tải trọng hàng hóa đang ở mức {ratio * 100:.0f}% công suất tối đa của xe"
                )

        can_optimize = len(errors) == 0
        return ValidationResponse(can_optimize=can_optimize, warnings=warnings, errors=errors)

    # -----------------------------------------------------------------------
    # Private helpers
    # -----------------------------------------------------------------------

    @staticmethod
    def _fits_any_rotation(pkg: PackageData, vt: VehicleTypeData) -> bool:
        """
        Kiểm tra package có fit thùng xe ở ít nhất 1 trong 6 rotations không.
        """
        dims = (pkg.length, pkg.width, pkg.height)
        for l, w, h in set(permutations(dims)):
            if l <= vt.inner_l and w <= vt.inner_w and h <= vt.inner_h:
                return True
        return False
