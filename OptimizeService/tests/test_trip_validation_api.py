"""
Integration tests for S3-02 · API Validate Trip

Seams under test:
  GET /api/v1/validate/trips/{trip_id} -> ApiResponse[ValidationResponse]

Acceptance criteria:
  - GET /api/v1/validate/trips/{trip_id} -> 200 + ValidationResponse (inside ApiResponse)
  - If has errors -> can_optimize = False
  - If only warnings -> can_optimize = True
  - If trip not found -> 404 Not Found
  - If invalid UUID format -> 422 Unprocessable Entity
"""
import uuid
import pytest
from starlette.testclient import TestClient

from app.main import app
from app.config.database import get_db
from app.service.optimize.trip_validation_service import (
    TripValidationService,
    TripData,
    VehicleTypeData,
    PackageData,
)


@pytest.fixture
def client():
    return TestClient(app)


class TestTripValidationApi:
    def test_validate_trip_success_returns_200_and_can_optimize_true(self, client):
        """Trip hợp lệ: trả về 200, success=True, can_optimize=True, errors=[]"""
        trip_id = uuid.uuid4()
        valid_trip = TripData(
            id=trip_id,
            vehicle_type=VehicleTypeData(
                inner_l=10.0, inner_w=2.5, inner_h=2.5, max_payload_kg=5000.0
            ),
            packages=[
                PackageData(
                    id=uuid.uuid4(),
                    length=1.0,
                    width=1.0,
                    height=1.0,
                    weight=50.0,
                )
            ],
        )

        # Mock service để cô lập DB seam
        class MockService(TripValidationService):
            def validate_trip_by_id(self, trip_id, db):
                return self.validate(valid_trip)

        from app.controller.optimization.trip_validation_router import get_trip_validation_service
        app.dependency_overrides[get_trip_validation_service] = lambda: MockService()
        app.dependency_overrides[get_db] = lambda: None

        try:
            response = client.get(f"/api/v1/validate/trips/{trip_id}")
            assert response.status_code == 200

            body = response.json()
            assert body["success"] is True
            assert "data" in body

            data = body["data"]
            assert data["can_optimize"] is True
            assert data["errors"] == []
            assert isinstance(data["warnings"], list)
        finally:
            app.dependency_overrides.clear()

    def test_validate_trip_with_errors_returns_200_and_can_optimize_false(self, client):
        """Trip có lỗi (vd: không có xe): trả về 200, can_optimize=False, errors non-empty"""
        trip_id = uuid.uuid4()
        invalid_trip = TripData(
            id=trip_id,
            vehicle_type=None,
            packages=[
                PackageData(
                    id=uuid.uuid4(),
                    length=1.0,
                    width=1.0,
                    height=1.0,
                    weight=50.0,
                )
            ],
        )

        class MockService(TripValidationService):
            def validate_trip_by_id(self, trip_id, db):
                return self.validate(invalid_trip)

        from app.controller.optimization.trip_validation_router import get_trip_validation_service
        app.dependency_overrides[get_trip_validation_service] = lambda: MockService()
        app.dependency_overrides[get_db] = lambda: None

        try:
            response = client.get(f"/api/v1/validate/trips/{trip_id}")
            assert response.status_code == 200

            body = response.json()
            assert body["success"] is True

            data = body["data"]
            assert data["can_optimize"] is False
            assert len(data["errors"]) > 0
        finally:
            app.dependency_overrides.clear()

    def test_validate_trip_with_warnings_returns_200_and_can_optimize_true(self, client):
        """Trip chỉ có warnings: trả về 200, can_optimize=True, warnings non-empty"""
        trip_id = uuid.uuid4()
        # Mock service trả về kết quả có warnings nhưng can_optimize=True
        from app.dto.response.validation_response import ValidationResponse

        class MockService(TripValidationService):
            def validate_trip_by_id(self, trip_id, db):
                return ValidationResponse(
                    can_optimize=True,
                    warnings=["Tải trọng hàng hóa đang ở mức 92% công suất tối đa"],
                    errors=[],
                )

        from app.controller.optimization.trip_validation_router import get_trip_validation_service
        app.dependency_overrides[get_trip_validation_service] = lambda: MockService()
        app.dependency_overrides[get_db] = lambda: None

        try:
            response = client.get(f"/api/v1/validate/trips/{trip_id}")
            assert response.status_code == 200

            body = response.json()
            assert body["success"] is True

            data = body["data"]
            assert data["can_optimize"] is True
            assert len(data["warnings"]) > 0
            assert data["errors"] == []
        finally:
            app.dependency_overrides.clear()

    def test_validate_trip_not_found_returns_404(self, client):
        """Trip không tồn tại: trả về 404 Not Found với mã lỗi TRIP_NOT_FOUND"""
        trip_id = uuid.uuid4()
        from app.exception.app_exception import AppException
        from app.exception.error_code import ErrorCode

        class MockService(TripValidationService):
            def validate_trip_by_id(self, trip_id, db):
                raise AppException(ErrorCode.TRIP_NOT_FOUND)

        from app.controller.optimization.trip_validation_router import get_trip_validation_service
        app.dependency_overrides[get_trip_validation_service] = lambda: MockService()
        app.dependency_overrides[get_db] = lambda: None

        try:
            response = client.get(f"/api/v1/validate/trips/{trip_id}")
            assert response.status_code == 404

            body = response.json()
            assert body["success"] is False
            assert body["code"] == "TRIP_NOT_FOUND"
        finally:
            app.dependency_overrides.clear()

    def test_validate_trip_invalid_uuid_returns_422(self, client):
        """trip_id không đúng định dạng UUID: FastAPI trả về 422 Unprocessable Entity"""
        response = client.get("/api/v1/validate/trips/not-a-valid-uuid")
        assert response.status_code == 422


class TestTripValidationWithRealSqliteDb:
    @pytest.fixture
    def db_session(self):
        from sqlalchemy import create_engine
        from sqlalchemy.pool import StaticPool
        from sqlalchemy.orm import sessionmaker
        from app.config.database import Base
        import app.entity.trip_model  # ensure models are registered

        sqlite_engine = create_engine(
            "sqlite:///:memory:",
            connect_args={"check_same_thread": False},
            poolclass=StaticPool,
        )
        Base.metadata.create_all(bind=sqlite_engine)
        TestingSessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=sqlite_engine)
        session = TestingSessionLocal()
        try:
            yield session
        finally:
            session.close()

    def test_end_to_end_with_valid_trip_in_db(self, client, db_session):
        """End-to-end với DB thật: Trip hợp lệ đầy đủ quan hệ trả về can_optimize=True"""
        from app.entity.trip_model import (
            Trip, Vehicle, VehicleType, DeliveryStop, TransportOrder, CargoPackage, PackageType
        )

        trip_id = uuid.uuid4()
        vt = VehicleType(
            name="Truck 5T",
            inner_length=6000,
            inner_width=2400,
            inner_height=2400,
            max_payload_kg=5000.0,
        )
        db_session.add(vt)
        db_session.flush()

        vehicle = Vehicle(vehicle_type_id=vt.id, license_plate="29A-12345", status="ACTIVE")
        db_session.add(vehicle)
        db_session.flush()

        trip = Trip(id=str(trip_id), vehicle_id=vehicle.id, trip_code="TRIP-001", status="PLANNED")
        db_session.add(trip)
        db_session.flush()

        stop = DeliveryStop(trip_id=str(trip_id), stop_sequence=1, stop_name="Stop 1")
        db_session.add(stop)
        db_session.flush()

        order = TransportOrder(delivery_stop_id=stop.id, order_code="ORD-001")
        db_session.add(order)
        db_session.flush()

        pt = PackageType(name="Box A", length=500, width=500, height=500)
        db_session.add(pt)
        db_session.flush()

        pkg = CargoPackage(order_id=order.id, package_type_id=pt.id, tracking_barcode="BC-001", actual_weight_kg=20.0)
        db_session.add(pkg)
        db_session.commit()

        app.dependency_overrides[get_db] = lambda: db_session
        try:
            response = client.get(f"/api/v1/validate/trips/{trip_id}")
            assert response.status_code == 200
            data = response.json()["data"]
            assert data["can_optimize"] is True
            assert data["errors"] == []
        finally:
            app.dependency_overrides.clear()

    def test_end_to_end_trip_not_found_in_db(self, client, db_session):
        """End-to-end với DB thật: Trip không tồn tại trong DB trả về 404"""
        non_existent_trip_id = uuid.uuid4()
        app.dependency_overrides[get_db] = lambda: db_session
        try:
            response = client.get(f"/api/v1/validate/trips/{non_existent_trip_id}")
            assert response.status_code == 404
            assert response.json()["code"] == "TRIP_NOT_FOUND"
        finally:
            app.dependency_overrides.clear()

    def test_end_to_end_trip_without_vehicle_in_db(self, client, db_session):
        """End-to-end với DB thật: Trip không có vehicle trả về can_optimize=False"""
        from app.entity.trip_model import Trip

        trip_id = uuid.uuid4()
        trip = Trip(id=str(trip_id), vehicle_id=None, trip_code="TRIP-NO-VEHICLE", status="PLANNED")
        db_session.add(trip)
        db_session.commit()

        app.dependency_overrides[get_db] = lambda: db_session
        try:
            response = client.get(f"/api/v1/validate/trips/{trip_id}")
            assert response.status_code == 200
            data = response.json()["data"]
            assert data["can_optimize"] is False
            assert any("vehicle" in e.lower() for e in data["errors"])
        finally:
            app.dependency_overrides.clear()

