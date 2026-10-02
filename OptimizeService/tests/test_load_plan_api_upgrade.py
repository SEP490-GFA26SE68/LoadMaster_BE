import pytest
from datetime import datetime, timedelta
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

import jwt
from fastapi.testclient import TestClient

from app.main import app
from app.config.database import Base, get_db
from app.entity.optimization.load_plan import LoadPlan
from app.entity.optimization.package_placement import PackagePlacement
from app.entity.optimization.optimization_job import OptimizationJob
from app.entity.trip_model import Trip, Vehicle, VehicleType, DeliveryStop
from app.service.optimize.load_plan_service import LoadPlanService
from app.dto.response.load_plan_response import LoadPlanResponse
from app.dto.response.package_placement_response import PackagePlacementResponse
from app.dto.response.load_plan_comparison_response import LoadPlanComparisonResponse


def make_jwt_token(roles: list[str], user_id: int = 99) -> str:
    payload = {
        "preferred_username": "dispatcher_user",
        "user_id": user_id,
        "roles": roles,
    }
    return jwt.encode(payload, "secret-test-key-32-bytes-minimum-length!", algorithm="HS256")


class TestLoadPlanApiUpgrade:
    @pytest.fixture
    def test_db(self):
        engine = create_engine(
            "sqlite:///:memory:",
            connect_args={"check_same_thread": False},
            poolclass=StaticPool,
            echo=False,
        )
        Base.metadata.create_all(engine)
        session_factory = sessionmaker(autocommit=False, autoflush=False, bind=engine)
        session = session_factory()

        # Seed data
        vt = VehicleType(id=1, name="Truck 5T", inner_length=6000, inner_width=2400, inner_height=2400, max_payload_kg=5000)
        v = Vehicle(id=1, vehicle_type_id=1, license_plate="29C-11111")
        trip = Trip(id="trip-cmp", vehicle_id=1)

        now = datetime.utcnow()
        stop1 = DeliveryStop(
            id=1,
            trip_id="trip-cmp",
            stop_sequence=1,
            stop_name="Kho Hà Nội",
            latitude=21.0285,
            longitude=105.8542,
            planned_arrival=now + timedelta(hours=2),
            deadline=now + timedelta(hours=3),
        )
        stop2 = DeliveryStop(
            id=2,
            trip_id="trip-cmp",
            stop_sequence=2,
            stop_name="Kho Phủ Lý",
            latitude=20.5400,
            longitude=105.9100,
            planned_arrival=now + timedelta(hours=6),
            deadline=now + timedelta(hours=5),  # 1 hour late
        )

        job = OptimizationJob(id=1, trip_id="trip-cmp", job_uuid="job-cmp-1", status="COMPLETED")

        # Plan 1: Basic
        plan1 = LoadPlan(
            id=101,
            job_id=1,
            plan_name="Plan Basic",
            packed_items_count=10,
            volume_utilization=0.65,
            weight_utilization=0.70,
            cog_x=2.5,
            cog_y=1.0,
            cog_z=0.8,
            front_axle_load=2000.0,
            rear_axle_load=1500.0,
            rehandling_count=2,
            approved=False,
        )

        # Plan 2: Optimized
        plan2 = LoadPlan(
            id=102,
            job_id=1,
            plan_name="Plan Optimized",
            packed_items_count=12,
            volume_utilization=0.80,
            weight_utilization=0.85,
            cog_x=3.0,
            cog_y=1.2,
            cog_z=0.9,
            front_axle_load=2200.0,
            rear_axle_load=1800.0,
            rehandling_count=0,
            approved=False,
        )

        placement1 = PackagePlacement(
            id=1,
            load_plan_id=101,
            package_id=10,
            pos_x=0.0,
            pos_y=0.0,
            pos_z=0.0,
            packed_length=1.0,
            packed_width=1.0,
            packed_height=1.0,
            rotation_type=0,
            step_sequence=1,
            stop_zone_id=1,
        )

        session.add_all([vt, v, trip, stop1, stop2, job, plan1, plan2, placement1])
        session.commit()

        yield session
        session.close()

    def test_load_plan_response_includes_3d_metrics_and_camel_case(self, test_db):
        """AC: LoadPlanResponse trả thêm cogX, cogY, cogZ, frontAxleLoad, rearAxleLoad, rehandlingCount."""
        service = LoadPlanService()
        plan = service.load_plan_repository.find_by_id(101, test_db)
        resp = service.to_response(plan, test_db)

        # Direct property access (snake_case)
        assert resp.cog_x == 2.5
        assert resp.cog_y == 1.0
        assert resp.cog_z == 0.8
        assert resp.front_axle_load == 2000.0
        assert resp.rear_axle_load == 1500.0
        assert resp.rehandling_count == 2

        # Dump with aliases (camelCase for API consumers)
        dump = resp.model_dump(by_alias=True)
        assert dump["cogX"] == 2.5
        assert dump["cogY"] == 1.0
        assert dump["cogZ"] == 0.8
        assert dump["frontAxleLoad"] == 2000.0
        assert dump["rearAxleLoad"] == 1500.0
        assert dump["rehandlingCount"] == 2

    def test_package_placement_response_includes_stop_zone_info(self, test_db):
        """AC: PackagePlacementResponse trả thêm stopZoneId, stopZoneName."""
        service = LoadPlanService()
        plan = service.load_plan_repository.find_by_id(101, test_db)
        resp = service.to_response(plan, test_db)

        assert len(resp.placements) == 1
        p = resp.placements[0]
        assert p.stop_zone_id == 1
        assert p.stop_zone_name == "Kho Hà Nội"

        dump = p.model_dump(by_alias=True)
        assert dump["stopZoneId"] == 1
        assert dump["stopZoneName"] == "Kho Hà Nội"

    def test_compare_plans_returns_metrics_diff_and_deadline_feasibility(self, test_db):
        """
        AC: GET /compare?planId1=&planId2= trả về:
          - COG diff
          - Axle load diff
          - Rehandling diff
          - Deadline feasibility
        """
        service = LoadPlanService()
        comparison = service.compare_plans(plan_id_1=101, plan_id_2=102, db=test_db)

        assert isinstance(comparison, LoadPlanComparisonResponse)
        assert comparison.plan1_id == 101
        assert comparison.plan2_id == 102

        # Metric diffs: plan2 - plan1
        assert comparison.volume_utilization.plan1 == 0.65
        assert comparison.volume_utilization.plan2 == 0.80
        assert comparison.volume_utilization.diff == 0.15

        # 3D Diffs
        assert comparison.cog_x.diff == 0.5  # 3.0 - 2.5
        assert comparison.front_axle_load.diff == 200.0  # 2200 - 2000
        assert comparison.rear_axle_load.diff == 300.0  # 1800 - 1500
        assert comparison.rehandling_count.diff == -2  # 0 - 2 (improved by 2)

        # Deadline feasibility: stop 2 planned_arrival > deadline -> is_feasible = False
        df = comparison.deadline_feasibility
        assert df.is_feasible is False
        assert len(df.stops) == 2
        late_stops = [s for s in df.stops if not s.is_feasible]
        assert len(late_stops) == 1
        assert late_stops[0].stop_name == "Kho Phủ Lý"
        assert late_stops[0].delay_minutes == 60.0

    def test_compare_plans_api_without_token_returns_401(self, test_db):
        app.dependency_overrides[get_db] = lambda: test_db
        client = TestClient(app)
        res = client.get("/api/v1/load-plans/compare?planId1=101&planId2=102")
        assert res.status_code == 401
        app.dependency_overrides.clear()

    def test_compare_plans_api_unauthorized_role_returns_403(self, test_db):
        app.dependency_overrides[get_db] = lambda: test_db
        client = TestClient(app)
        token = make_jwt_token(["DRIVER"])
        res = client.get(
            "/api/v1/load-plans/compare?planId1=101&planId2=102",
            headers={"Authorization": f"Bearer {token}"},
        )
        assert res.status_code == 403
        app.dependency_overrides.clear()

    def test_compare_plans_api_dispatcher_returns_200_and_comparison_data(self, test_db):
        app.dependency_overrides[get_db] = lambda: test_db
        client = TestClient(app)
        token = make_jwt_token(["DISPATCHER"])
        res = client.get(
            "/api/v1/load-plans/compare?planId1=101&planId2=102",
            headers={"Authorization": f"Bearer {token}"},
        )
        assert res.status_code == 200
        body = res.json()
        assert body["success"] is True
        data = body["data"]

        # Verify camelCase aliases in JSON response
        assert data["plan1Id"] == 101
        assert data["plan2Id"] == 102
        assert data["cogX"]["diff"] == 0.5
        assert data["frontAxleLoad"]["diff"] == 200.0
        assert data["rearAxleLoad"]["diff"] == 300.0
        assert data["rehandlingCount"]["diff"] == -2
        assert data["deadlineFeasibility"]["isFeasible"] is False
        assert len(data["deadlineFeasibility"]["stops"]) == 2

        app.dependency_overrides.clear()
