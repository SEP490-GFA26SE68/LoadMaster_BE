"""Schema-v3.4 integration tests for the S3-01 service seam."""

from decimal import Decimal

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from app.config.database import Base
from app.entity.trip_model import (
    DeliveryStop,
    Order,
    Package,
    PackageType,
    Trip,
    Vehicle,
    VehicleType,
)
from app.service.optimize.trip_validation_service import TripValidationService


@pytest.fixture
def db_session():
    engine = create_engine(
        "sqlite:///:memory:",
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    Base.metadata.create_all(bind=engine)
    session = sessionmaker(bind=engine, autoflush=False, autocommit=False)()
    try:
        yield session
    finally:
        session.close()
        engine.dispose()


def test_valid_numeric_trip_using_schema_v34_can_optimize(db_session):
    """A caller can validate a valid BIGINT trip through the public service."""
    vehicle_type = VehicleType(
        company_id=1,
        name="Xe tải 5 tấn",
        inner_length=400,
        inner_width=550,
        inner_height=300,
        max_payload_kg=Decimal("5000.00"),
    )
    db_session.add(vehicle_type)
    db_session.flush()

    vehicle = Vehicle(
        company_id=1,
        vehicle_type_id=vehicle_type.id,
        license_plate="29A-12345",
        front_axle_limit_kg=Decimal("2500.00"),
        rear_axle_limit_kg=Decimal("5000.00"),
    )
    db_session.add(vehicle)
    db_session.flush()

    trip = Trip(
        id=1,
        company_id=1,
        trip_code="TRIP-001",
        vehicle_id=vehicle.id,
        created_by_dispatcher_id=10,
        status="DRAFT",
    )
    db_session.add(trip)
    db_session.flush()

    stop = DeliveryStop(
        trip_id=trip.id,
        stop_sequence=1,
        location_address="Hà Nội",
    )
    db_session.add(stop)
    db_session.flush()

    order = Order(
        company_id=1,
        order_code="ORD-001",
        customer_id=20,
        delivery_stop_id=stop.id,
        total_weight_kg=Decimal("20.00"),
    )
    db_session.add(order)
    db_session.flush()

    package_type = PackageType(
        company_id=1,
        name="Thùng A",
        # Catalogue length deliberately differs: S3-01 uses Package.actualLength.
        length=700,
        width=400,
        height=300,
        weight_kg=Decimal("18.00"),
        max_stacking_weight_kg=Decimal("50.00"),
        is_fragile=False,
    )
    db_session.add(package_type)
    db_session.flush()

    package = Package(
        order_id=order.id,
        package_type_id=package_type.id,
        tracking_barcode="BC-001",
        actual_length=550,
        actual_weight_kg=Decimal("20.00"),
        is_pinned=False,
    )
    db_session.add(package)
    db_session.commit()

    result = TripValidationService().validate_trip_by_id(trip.id, db_session)

    assert result.can_optimize is True
    assert result.errors == []


def test_trip_without_a_resolvable_vehicle_cannot_optimize(db_session):
    """A trip whose assigned vehicle cannot be loaded is rejected."""
    trip = Trip(
        id=1,
        company_id=1,
        trip_code="TRIP-NO-VEHICLE",
        vehicle_id=999,
        created_by_dispatcher_id=10,
        status="DRAFT",
    )
    db_session.add(trip)
    db_session.commit()

    result = TripValidationService().validate_trip_by_id(trip.id, db_session)

    assert result.can_optimize is False
    assert any("vehicle" in error.lower() for error in result.errors)


def test_trip_without_packages_cannot_optimize(db_session):
    """At least one package must be assigned through a delivery stop."""
    vehicle_type = VehicleType(
        company_id=1,
        name="Xe rỗng",
        inner_length=6000,
        inner_width=2400,
        inner_height=2400,
        max_payload_kg=Decimal("5000.00"),
    )
    db_session.add(vehicle_type)
    db_session.flush()
    vehicle = Vehicle(
        company_id=1,
        vehicle_type_id=vehicle_type.id,
        license_plate="29A-EMPTY",
        front_axle_limit_kg=Decimal("2500.00"),
        rear_axle_limit_kg=Decimal("5000.00"),
    )
    db_session.add(vehicle)
    db_session.flush()
    trip = Trip(
        id=1,
        company_id=1,
        trip_code="TRIP-EMPTY",
        vehicle_id=vehicle.id,
        created_by_dispatcher_id=10,
        status="DRAFT",
    )
    db_session.add(trip)
    db_session.commit()

    result = TripValidationService().validate_trip_by_id(trip.id, db_session)

    assert result.can_optimize is False
    assert any("package" in error.lower() for error in result.errors)


def test_trip_over_vehicle_payload_cannot_optimize(db_session):
    """Actual package weights, not catalogue weights, enforce payload."""
    vehicle_type = VehicleType(
        company_id=1,
        name="Xe tải nhẹ",
        inner_length=6000,
        inner_width=2400,
        inner_height=2400,
        max_payload_kg=Decimal("10.00"),
    )
    db_session.add(vehicle_type)
    db_session.flush()
    vehicle = Vehicle(
        company_id=1,
        vehicle_type_id=vehicle_type.id,
        license_plate="29A-HEAVY",
        front_axle_limit_kg=Decimal("2500.00"),
        rear_axle_limit_kg=Decimal("5000.00"),
    )
    db_session.add(vehicle)
    db_session.flush()
    trip = Trip(
        id=1,
        company_id=1,
        trip_code="TRIP-HEAVY",
        vehicle_id=vehicle.id,
        created_by_dispatcher_id=10,
        status="DRAFT",
    )
    db_session.add(trip)
    db_session.flush()
    stop = DeliveryStop(trip_id=trip.id, stop_sequence=1, location_address="Hà Nội")
    db_session.add(stop)
    db_session.flush()
    order = Order(
        company_id=1,
        order_code="ORD-HEAVY",
        customer_id=20,
        delivery_stop_id=stop.id,
        total_weight_kg=Decimal("20.00"),
    )
    db_session.add(order)
    db_session.flush()
    package_type = PackageType(
        company_id=1,
        name="Thùng nặng",
        length=500,
        width=400,
        height=300,
        weight_kg=Decimal("5.00"),
        max_stacking_weight_kg=Decimal("50.00"),
        is_fragile=False,
    )
    db_session.add(package_type)
    db_session.flush()
    db_session.add(
        Package(
            order_id=order.id,
            package_type_id=package_type.id,
            tracking_barcode="BC-HEAVY",
            actual_length=500,
            actual_weight_kg=Decimal("20.00"),
            is_pinned=False,
        )
    )
    db_session.commit()

    result = TripValidationService().validate_trip_by_id(trip.id, db_session)

    assert result.can_optimize is False
    assert any("payload" in error.lower() for error in result.errors)


def test_trip_over_vehicle_volume_cannot_optimize(db_session):
    """Package volume is compared in deterministic mm-to-m conversion."""
    vehicle_type = VehicleType(
        company_id=1,
        name="Xe một mét khối",
        inner_length=1000,
        inner_width=1000,
        inner_height=1000,
        max_payload_kg=Decimal("5000.00"),
    )
    db_session.add(vehicle_type)
    db_session.flush()
    vehicle = Vehicle(
        company_id=1,
        vehicle_type_id=vehicle_type.id,
        license_plate="29A-VOLUME",
        front_axle_limit_kg=Decimal("2500.00"),
        rear_axle_limit_kg=Decimal("5000.00"),
    )
    db_session.add(vehicle)
    db_session.flush()
    trip = Trip(
        id=1,
        company_id=1,
        trip_code="TRIP-VOLUME",
        vehicle_id=vehicle.id,
        created_by_dispatcher_id=10,
        status="DRAFT",
    )
    db_session.add(trip)
    db_session.flush()
    stop = DeliveryStop(trip_id=trip.id, stop_sequence=1, location_address="Hà Nội")
    db_session.add(stop)
    db_session.flush()
    order = Order(
        company_id=1,
        order_code="ORD-VOLUME",
        customer_id=20,
        delivery_stop_id=stop.id,
        total_weight_kg=Decimal("1.00"),
    )
    db_session.add(order)
    db_session.flush()
    package_type = PackageType(
        company_id=1,
        name="Thùng hai mét khối",
        length=2000,
        width=1000,
        height=1000,
        weight_kg=Decimal("1.00"),
        max_stacking_weight_kg=Decimal("0.00"),
        is_fragile=False,
    )
    db_session.add(package_type)
    db_session.flush()
    db_session.add(
        Package(
            order_id=order.id,
            package_type_id=package_type.id,
            tracking_barcode="BC-VOLUME",
            actual_length=2000,
            actual_weight_kg=Decimal("1.00"),
            is_pinned=False,
        )
    )
    db_session.commit()

    result = TripValidationService().validate_trip_by_id(trip.id, db_session)

    assert result.can_optimize is False
    assert any("volume" in error.lower() for error in result.errors)
