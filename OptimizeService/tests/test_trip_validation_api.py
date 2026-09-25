"""HTTP contract tests for S3-02 against the schema-v3.4 identifiers."""

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool
from starlette.testclient import TestClient

from app.config.database import Base, get_db
from app.entity.trip_model import (
    DeliveryStop,
    Order,
    Package,
    PackageType,
    Trip,
    Vehicle,
    VehicleType,
)
from app.main import app


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


@pytest.fixture
def client(db_session):
    app.dependency_overrides[get_db] = lambda: db_session
    try:
        with TestClient(app) as test_client:
            yield test_client
    finally:
        app.dependency_overrides.clear()


def add_valid_trip(
    db_session,
    trip_id: int,
    *,
    max_payload_kg: float = 5000,
    package_weight_kg: float = 20,
) -> None:
    vehicle_type = VehicleType(
        company_id=1,
        name="Schema v3.4 truck",
        inner_length=6000,
        inner_width=2400,
        inner_height=2400,
        max_payload_kg=max_payload_kg,
    )
    db_session.add(vehicle_type)
    db_session.flush()

    vehicle = Vehicle(
        company_id=1,
        vehicle_type_id=vehicle_type.id,
        license_plate=f"API-{trip_id}",
        front_axle_limit_kg=3000,
        rear_axle_limit_kg=5000,
    )
    db_session.add(vehicle)
    db_session.flush()

    trip = Trip(
        id=trip_id,
        company_id=1,
        trip_code=f"TRIP-{trip_id}",
        vehicle_id=vehicle.id,
        created_by_dispatcher_id=1,
        status="DRAFT",
    )
    db_session.add(trip)
    db_session.flush()

    stop = DeliveryStop(
        trip_id=trip_id,
        stop_sequence=1,
        location_address="Ho Chi Minh City",
    )
    db_session.add(stop)
    db_session.flush()

    order = Order(
        company_id=1,
        order_code=f"ORDER-{trip_id}",
        customer_id=1,
        delivery_stop_id=stop.id,
        total_weight_kg=package_weight_kg,
    )
    db_session.add(order)
    db_session.flush()

    package_type = PackageType(
        company_id=1,
        name="API box",
        length=500,
        width=400,
        height=300,
        weight_kg=package_weight_kg,
        max_stacking_weight_kg=100,
        is_fragile=False,
    )
    db_session.add(package_type)
    db_session.flush()

    db_session.add(
        Package(
            order_id=order.id,
            package_type_id=package_type.id,
            tracking_barcode=f"PKG-{trip_id}",
            actual_length=500,
            actual_weight_kg=package_weight_kg,
            is_pinned=False,
        )
    )
    db_session.commit()


def test_numeric_trip_id_returns_validation_response(client, db_session):
    add_valid_trip(db_session, trip_id=3401)

    response = client.get("/api/v1/validate/trips/3401")

    assert response.status_code == 200
    assert response.json() == {
        "success": True,
        "code": None,
        "message": "Kết quả kiểm tra chuyến đi",
        "data": {"can_optimize": True, "warnings": [], "errors": []},
        "errors": None,
    }


def test_trip_with_validation_errors_returns_can_optimize_false(client, db_session):
    db_session.add(
        Trip(
            id=3402,
            company_id=1,
            trip_code="TRIP-3402",
            vehicle_id=None,
            created_by_dispatcher_id=1,
            status="DRAFT",
        )
    )
    db_session.commit()

    response = client.get("/api/v1/validate/trips/3402")

    assert response.status_code == 200
    data = response.json()["data"]
    assert data["can_optimize"] is False
    assert data["warnings"] == []
    assert data["errors"] == [
        "Trip chưa được gán vehicle: không thể tính toán thùng xe"
    ]


def test_trip_with_only_warnings_remains_optimizable(client, db_session):
    add_valid_trip(
        db_session,
        trip_id=3403,
        max_payload_kg=100,
        package_weight_kg=91,
    )

    response = client.get("/api/v1/validate/trips/3403")

    assert response.status_code == 200
    data = response.json()["data"]
    assert data["can_optimize"] is True
    assert data["errors"] == []
    assert data["warnings"] == [
        "Tải trọng hàng hóa đang ở mức 91% công suất tối đa của xe"
    ]


def test_unknown_numeric_trip_id_returns_not_found(client):
    response = client.get("/api/v1/validate/trips/999999")

    assert response.status_code == 404
    assert response.json() == {
        "success": False,
        "code": "TRIP_NOT_FOUND",
        "message": "Không tìm thấy chuyến đi",
        "data": None,
        "errors": None,
    }


def test_non_numeric_trip_id_returns_validation_error(client):
    response = client.get("/api/v1/validate/trips/not-a-number")

    assert response.status_code == 422
    body = response.json()
    assert body["success"] is False
    assert body["code"] == "VALIDATION_ERROR"
    assert body["data"] is None
    assert body["errors"][0]["loc"] == ["path", "trip_id"]
