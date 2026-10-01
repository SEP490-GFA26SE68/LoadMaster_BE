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

import uuid
from dataclasses import dataclass, field
from itertools import permutations
from typing import Optional, Any
from uuid import UUID

from app.dto.response.validation_response import ValidationResponse
from app.exception.app_exception import AppException
from app.exception.error_code import ErrorCode


# ---------------------------------------------------------------------------
# Data transfer objects (input) — decoupled khỏi ORM layer
# ---------------------------------------------------------------------------

@dataclass
class VehicleTypeData:
    """Thông số thùng xe."""
    inner_l: float  # chiều dài trong (m)
    inner_w: float  # chiều rộng trong (m)
    inner_h: float  # chiều cao trong (m)
    max_payload_kg: float


@dataclass
class StopData:
    """Thông tin một điểm dừng giao hàng."""
    id: Any
    sequence: int = 1
    latitude: Optional[float] = None
    longitude: Optional[float] = None


@dataclass
class PackageData:
    """Thông số một kiện hàng."""
    id: UUID | Any
    length: float
    width: float
    height: float
    weight: float
    handling_class: Optional[str] = "STANDARD"
    fragile: bool = False
    max_stack_weight_kg: Optional[float] = None
    stop_id: Optional[Any] = None


@dataclass
class TripData:
    """Dữ liệu trip cần validate."""
    id: UUID | Any
    vehicle_type: Optional[VehicleTypeData]
    packages: list[PackageData] = field(default_factory=list)
    stops: list[StopData] = field(default_factory=list)
    override_reason: Optional[str] = None


# ---------------------------------------------------------------------------
# Service
# ---------------------------------------------------------------------------

class TripValidationService:
    """
    Validate trip trước khi submit optimization job (S5b-06).
    """

    WEIGHT_WARNING_THRESHOLD = 0.90

    def validate_trip_by_id(self, trip_id: UUID, db: Any) -> ValidationResponse:
        """
        Validate trip bằng cách load từ database qua Session SQLAlchemy.
        """
        from app.entity.trip_model import Trip, CargoPackage, TransportOrder, DeliveryStop

        if db is None:
            raise AppException(ErrorCode.TRIP_NOT_FOUND)

        trip = db.query(Trip).filter(Trip.id == str(trip_id)).first()
        if not trip:
            raise AppException(ErrorCode.TRIP_NOT_FOUND)

        # Vehicle type
        vehicle_type_data = None
        if trip.vehicle and trip.vehicle.vehicle_type:
            vt = trip.vehicle.vehicle_type
            inner_l = float(vt.inner_length) / 1000.0 if (vt.inner_length and vt.inner_length > 50) else float(vt.inner_length or 0)
            inner_w = float(vt.inner_width) / 1000.0 if (vt.inner_width and vt.inner_width > 50) else float(vt.inner_width or 0)
            inner_h = float(vt.inner_height) / 1000.0 if (vt.inner_height and vt.inner_height > 50) else float(vt.inner_height or 0)
            payload = float(vt.max_payload_kg or 0)
            vehicle_type_data = VehicleTypeData(
                inner_l=inner_l,
                inner_w=inner_w,
                inner_h=inner_h,
                max_payload_kg=payload,
            )

        # Stops
        stops_query = (
            db.query(DeliveryStop)
            .filter(DeliveryStop.trip_id == str(trip_id))
            .order_by(DeliveryStop.stop_sequence)
            .all()
        )
        stops_data = [
            StopData(
                id=s.id,
                sequence=s.stop_sequence if s.stop_sequence is not None else (idx + 1),
                latitude=float(s.latitude) if getattr(s, "latitude", None) is not None else None,
                longitude=float(s.longitude) if getattr(s, "longitude", None) is not None else None,
            )
            for idx, s in enumerate(stops_query)
        ]

        # Packages via DeliveryStop -> Order -> CargoPackage
        packages_query = (
            db.query(CargoPackage, DeliveryStop)
            .join(TransportOrder, CargoPackage.order_id == TransportOrder.id)
            .join(DeliveryStop, TransportOrder.delivery_stop_id == DeliveryStop.id)
            .filter(DeliveryStop.trip_id == str(trip_id))
            .all()
        )

        packages_data: list[PackageData] = []
        for pkg, stop in packages_query:
            w = float(pkg.actual_weight_kg or 0)
            pt = pkg.package_type
            if pt:
                l = float(pt.length) / 1000.0 if (pt.length and pt.length > 50) else float(pt.length or 0)
                width = float(pt.width) / 1000.0 if (pt.width and pt.width > 50) else float(pt.width or 0)
                h = float(pt.height) / 1000.0 if (pt.height and pt.height > 50) else float(pt.height or 0)
                fragile = getattr(pt, "is_fragile", False) or getattr(pt, "fragile", False) or False
                max_stack = float(pt.max_stack_weight_kg) if getattr(pt, "max_stack_weight_kg", None) is not None else None
            else:
                l, width, h = 0.0, 0.0, 0.0
                fragile = False
                max_stack = None

            pkg_id = pkg.id
            try:
                pkg_id = UUID(str(pkg.id))
            except Exception:
                pass

            handling_cls = getattr(pkg, "handling_class", "STANDARD") or "STANDARD"

            packages_data.append(
                PackageData(
                    id=pkg_id,
                    length=l,
                    width=width,
                    height=h,
                    weight=w,
                    handling_class=handling_cls,
                    fragile=bool(fragile),
                    max_stack_weight_kg=max_stack,
                    stop_id=stop.id,
                )
            )

        trip_data = TripData(
            id=trip_id,
            vehicle_type=vehicle_type_data,
            packages=packages_data,
            stops=stops_data,
            override_reason=getattr(trip, "override_reason", None),
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

        # AC6 — Tất cả packages có cùng handling_class (hoặc trip có override_reason)
        if not trip.override_reason or not str(trip.override_reason).strip():
            handling_classes = {
                str(p.handling_class).strip().upper()
                for p in trip.packages
                if p.handling_class
            }
            if len(handling_classes) > 1:
                errors.append(
                    f"INCOMPATIBLE_HANDLING_CLASS: Các kiện hàng trong trip có handling_class không đồng nhất "
                    f"({', '.join(sorted(handling_classes))}) và trip không có override_reason"
                )

        # AC7 — Sơ bộ COG estimate: nếu tất cả hàng nặng đều lệch hẳn về một phía (> 85% tải trọng)
        if trip.stops and len(trip.stops) >= 2:
            sorted_stops = sorted(trip.stops, key=lambda s: s.sequence)
            mid = len(sorted_stops) // 2
            first_half_ids = {str(s.id) for s in sorted_stops[:mid]}
            second_half_ids = {str(s.id) for s in sorted_stops[mid:]}

            w1 = sum(p.weight for p in trip.packages if p.stop_id is not None and str(p.stop_id) in first_half_ids)
            w2 = sum(p.weight for p in trip.packages if p.stop_id is not None and str(p.stop_id) in second_half_ids)
            total_assigned = w1 + w2

            if total_assigned > 0:
                ratio1 = w1 / total_assigned
                ratio2 = w2 / total_assigned
                if ratio1 > 0.85 or ratio2 > 0.85:
                    warnings.append(
                        f"COG_RISK: Phân bổ tải trọng hàng hóa bị lệch về một phía "
                        f"({max(ratio1, ratio2) * 100:.0f}% tổng tải trọng), có nguy cơ mất cân bằng trọng tâm"
                    )

        # AC8 — Mỗi stop phải có lat/lng (cần cho route opt)
        if trip.stops:
            for s in trip.stops:
                if s.latitude is None or s.longitude is None:
                    errors.append(
                        f"MISSING_STOP_COORDINATES: Stop {s.id} (sequence {s.sequence}) thiếu tọa độ lat/lng"
                    )

        # AC9 — max_stack_weight_kg kiểm tra xếp chồng lên hàng dễ vỡ (fragile)
        if vt:
            floor_area = float(vt.inner_l * vt.inner_w)
            total_footprint = sum(float(p.length * p.width) for p in trip.packages)
            stacking_unavoidable = floor_area > 0 and (total_footprint > floor_area + 1e-4)

            for p in trip.packages:
                if p.fragile:
                    max_stack = p.max_stack_weight_kg if p.max_stack_weight_kg is not None else 0.0
                    if stacking_unavoidable:
                        for other in trip.packages:
                            if other.id != p.id and other.weight > max_stack + 1e-4:
                                errors.append(
                                    f"STACK_WEIGHT_EXCEEDED: Kiện hàng {p.id} là hàng dễ vỡ (FRAGILE) "
                                    f"với max_stack_weight={max_stack:.1f}kg, nhưng kiện {other.id} "
                                    f"có trọng lượng {other.weight:.1f}kg vượt quá giới hạn và thùng xe "
                                    f"không đủ diện tích sàn (bắt buộc phải xếp chồng)"
                                )
                                break

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
