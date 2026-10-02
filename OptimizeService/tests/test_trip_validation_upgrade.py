import pytest
from uuid import uuid4

from app.service.optimize.trip_validation_service import (
    TripValidationService,
    TripData,
    VehicleTypeData,
    PackageData,
    StopData,
)


class TestTripValidationUpgrade:
    def setup_method(self):
        self.service = TripValidationService()
        self.vehicle = VehicleTypeData(
            inner_l=6.0,
            inner_w=2.0,
            inner_h=2.0,
            max_payload_kg=5000.0,
        )

    # -------------------------------------------------------------------------
    # AC6: Handling class consistency
    # -------------------------------------------------------------------------
    def test_handling_class_consistency_same_class_passes(self):
        """Tất cả packages có cùng handling_class -> Hợp lệ."""
        service = TripValidationService()
        p1 = PackageData(id=uuid4(), length=1.0, width=1.0, height=1.0, weight=10.0, handling_class="FRAGILE")
        p2 = PackageData(id=uuid4(), length=1.0, width=1.0, height=1.0, weight=20.0, handling_class="FRAGILE")
        trip = TripData(id=uuid4(), vehicle_type=self.vehicle, packages=[p1, p2])

        res = service.validate(trip)
        assert res.can_optimize is True
        assert len(res.errors) == 0

    def test_handling_class_mismatch_without_override_fails(self):
        """Mixed handling_class mà không có override_reason -> Lỗi INCOMPATIBLE_HANDLING_CLASS."""
        service = TripValidationService()
        p1 = PackageData(id=uuid4(), length=1.0, width=1.0, height=1.0, weight=10.0, handling_class="STANDARD")
        p2 = PackageData(id=uuid4(), length=1.0, width=1.0, height=1.0, weight=20.0, handling_class="REFRIGERATED")
        trip = TripData(id=uuid4(), vehicle_type=self.vehicle, packages=[p1, p2], override_reason=None)

        res = service.validate(trip)
        assert res.can_optimize is False
        assert any("INCOMPATIBLE_HANDLING_CLASS" in err or "handling_class" in err.lower() for err in res.errors)

    def test_handling_class_mismatch_with_override_reason_passes(self):
        """Mixed handling_class nhưng CÓ override_reason -> Hợp lệ."""
        service = TripValidationService()
        p1 = PackageData(id=uuid4(), length=1.0, width=1.0, height=1.0, weight=10.0, handling_class="STANDARD")
        p2 = PackageData(id=uuid4(), length=1.0, width=1.0, height=1.0, weight=20.0, handling_class="REFRIGERATED")
        trip = TripData(
            id=uuid4(),
            vehicle_type=self.vehicle,
            packages=[p1, p2],
            override_reason="Approved by Dispatcher Manager for urgent consolidated shipment",
        )

        res = service.validate(trip)
        assert res.can_optimize is True

    # -------------------------------------------------------------------------
    # AC7: Sơ bộ COG estimate
    # -------------------------------------------------------------------------
    def test_cog_risk_warning_when_heavy_cargo_skewed_to_one_side(self):
        """Hàng nặng lệch hẳn về một phía (> 85% tổng tải trọng) -> Warning COG_RISK."""
        service = TripValidationService()
        stop1 = StopData(id="s1", sequence=1, latitude=10.1, longitude=106.1)
        stop2 = StopData(id="s2", sequence=2, latitude=10.2, longitude=106.2)

        # Stop 1 has 950kg, Stop 2 has 50kg -> 95% at Stop 1
        p1 = PackageData(id=uuid4(), length=1.0, width=1.0, height=1.0, weight=950.0, stop_id="s1")
        p2 = PackageData(id=uuid4(), length=1.0, width=1.0, height=1.0, weight=50.0, stop_id="s2")

        trip = TripData(
            id=uuid4(),
            vehicle_type=self.vehicle,
            packages=[p1, p2],
            stops=[stop1, stop2],
        )

        res = service.validate(trip)
        assert res.can_optimize is True
        assert any("COG_RISK" in w for w in res.warnings)

    def test_cog_balanced_no_risk_warning(self):
        """Tải trọng cân bằng giữa các stop -> Không có warning COG_RISK."""
        service = TripValidationService()
        stop1 = StopData(id="s1", sequence=1, latitude=10.1, longitude=106.1)
        stop2 = StopData(id="s2", sequence=2, latitude=10.2, longitude=106.2)

        p1 = PackageData(id=uuid4(), length=1.0, width=1.0, height=1.0, weight=500.0, stop_id="s1")
        p2 = PackageData(id=uuid4(), length=1.0, width=1.0, height=1.0, weight=500.0, stop_id="s2")

        trip = TripData(
            id=uuid4(),
            vehicle_type=self.vehicle,
            packages=[p1, p2],
            stops=[stop1, stop2],
        )

        res = service.validate(trip)
        assert not any("COG_RISK" in w for w in res.warnings)

    # -------------------------------------------------------------------------
    # AC8: Stop coordinates validation
    # -------------------------------------------------------------------------
    def test_missing_stop_coordinates_fails(self):
        """Stop thiếu lat hoặc lng -> Lỗi MISSING_STOP_COORDINATES."""
        service = TripValidationService()
        stop_valid = StopData(id="s1", sequence=1, latitude=10.1, longitude=106.1)
        stop_missing = StopData(id="s2", sequence=2, latitude=None, longitude=106.2)

        p = PackageData(id=uuid4(), length=1.0, width=1.0, height=1.0, weight=50.0, stop_id="s1")
        trip = TripData(
            id=uuid4(),
            vehicle_type=self.vehicle,
            packages=[p],
            stops=[stop_valid, stop_missing],
        )

        res = service.validate(trip)
        assert res.can_optimize is False
        assert any("MISSING_STOP_COORDINATES" in err for err in res.errors)

    def test_valid_stop_coordinates_passes(self):
        """Tất cả stops có đầy đủ tọa độ -> Hợp lệ."""
        service = TripValidationService()
        stop1 = StopData(id="s1", sequence=1, latitude=10.1, longitude=106.1)
        stop2 = StopData(id="s2", sequence=2, latitude=10.2, longitude=106.2)

        p = PackageData(id=uuid4(), length=1.0, width=1.0, height=1.0, weight=50.0, stop_id="s1")
        trip = TripData(
            id=uuid4(),
            vehicle_type=self.vehicle,
            packages=[p],
            stops=[stop1, stop2],
        )

        res = service.validate(trip)
        assert res.can_optimize is True
        assert not any("MISSING_STOP_COORDINATES" in err for err in res.errors)

    # -------------------------------------------------------------------------
    # AC9: max_stack_weight_kg validation
    # -------------------------------------------------------------------------
    def test_fragile_package_with_excessive_stack_weight_when_stacking_unavoidable(self):
        """
        Kiện hàng fragile có max_stack_weight = 0kg, thùng xe diện tích sàn không đủ (bắt buộc phải xếp chồng),
        và có kiện hàng khác nặng hơn -> Lỗi STACK_WEIGHT_EXCEEDED.
        """
        service = TripValidationService()
        # Thùng xe nhỏ: floor 1.0 x 1.0 = 1.0m2, height 2.0m
        small_vt = VehicleTypeData(inner_l=1.0, inner_w=1.0, inner_h=2.0, max_payload_kg=1000.0)

        # 2 packages, mỗi package footprint 1.0 x 1.0 = 1.0m2 -> tổng footprint 2.0m2 > 1.0m2 (bắt buộc xếp chồng)
        p_fragile = PackageData(
            id=uuid4(), length=1.0, width=1.0, height=0.5, weight=10.0, fragile=True, max_stack_weight_kg=0.0
        )
        p_heavy = PackageData(
            id=uuid4(), length=1.0, width=1.0, height=0.5, weight=50.0, fragile=False
        )

        trip = TripData(id=uuid4(), vehicle_type=small_vt, packages=[p_fragile, p_heavy])
        res = service.validate(trip)
        assert res.can_optimize is False
        assert any("STACK_WEIGHT_EXCEEDED" in err or "fragile" in err.lower() for err in res.errors)
