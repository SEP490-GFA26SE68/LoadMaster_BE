"""
Tests for S3-11 · API Approve Plan

Seams under test:
  - LoadPlanService.approve_plan(plan_id, current_user_id, db) -> LoadPlan
  - Validation:
    - plan exists (LOAD_PLAN_NOT_FOUND)
    - not already approved (PLAN_ALREADY_APPROVED)
    - has placements (PLAN_HAS_NO_PLACEMENTS)
    - valid LIFO sequence (PLAN_LIFO_INVALID)
  - Audit log inserted with action PLAN_APPROVED
  - POST /api/v1/load-plans/{id}/approve endpoint (auth, 200, 400, 403, 404)
"""
import uuid
import pytest
import jwt
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool
from starlette.testclient import TestClient

from app.main import app
from app.config.database import Base, get_db
from app.constant.optimization.job_status import OptimizationJobStatus
from app.entity.common.audit_log import AuditLog
from app.entity.optimization.optimization_job import OptimizationJob
from app.entity.optimization.load_plan import LoadPlan
from app.entity.optimization.package_placement import PackagePlacement
from app.entity.trip_model import Trip
from app.exception.app_exception import AppException
from app.exception.error_code import ErrorCode
from app.service.optimize.load_plan_service import LoadPlanService


def make_jwt_token(roles: list[str], user_id: int = 99) -> str:
    payload = {
        "preferred_username": "dispatcher_user",
        "user_id": user_id,
        "roles": roles,
    }
    return jwt.encode(payload, "secret-test-key-32-bytes-minimum-length!", algorithm="HS256")


@pytest.fixture
def db_session():
    engine = create_engine(
        "sqlite:///:memory:",
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
        echo=False,
    )
    Base.metadata.create_all(engine)
    TestingSession = sessionmaker(autocommit=False, autoflush=False, bind=engine)
    session = TestingSession()

    trip = Trip(id=str(uuid.uuid4()), status="DRAFT")
    session.add(trip)
    session.commit()

    job = OptimizationJob(
        job_uuid=str(uuid.uuid4()),
        trip_id=trip.id,
        status=OptimizationJobStatus.COMPLETED.value,
    )
    session.add(job)
    session.commit()

    yield session
    session.close()


@pytest.fixture
def plan_with_placements(db_session):
    job = db_session.query(OptimizationJob).first()
    plan = LoadPlan(
        job_id=job.id,
        plan_name="Test Plan",
        packed_items_count=2,
        volume_utilization=0.8,
        weight_utilization=0.7,
        approved=False,
    )
    db_session.add(plan)
    db_session.commit()

    p1 = PackagePlacement(load_plan_id=plan.id, package_id=1, step_sequence=1, pos_x=0, pos_y=0, pos_z=0)
    p2 = PackagePlacement(load_plan_id=plan.id, package_id=2, step_sequence=2, pos_x=1, pos_y=0, pos_z=0)
    db_session.add_all([p1, p2])
    db_session.commit()
    return plan


class TestLoadPlanServiceApproval:
    def test_approve_plan_success_sets_approved_and_creates_audit_log(self, db_session, plan_with_placements):
        """Duyệt plan thành công: approved=True, approved_by_id được gán, và audit_log được ghi"""
        service = LoadPlanService()
        user_id = 123

        approved_plan = service.approve_plan(plan_with_placements.id, current_user_id=user_id, db=db_session)

        assert approved_plan.approved is True
        assert approved_plan.approved_by_id == user_id

        # Verify audit_log in DB
        log = db_session.query(AuditLog).filter(
            AuditLog.entity_name == "LOAD_PLAN",
            AuditLog.entity_id == str(plan_with_placements.id),
        ).first()
        assert log is not None
        assert log.action_type == "PLAN_APPROVED"
        assert log.user_id == user_id

    def test_approve_plan_not_found_raises_exception(self, db_session):
        """Plan không tồn tại -> LOAD_PLAN_NOT_FOUND (404)"""
        service = LoadPlanService()
        with pytest.raises(AppException) as exc_info:
            service.approve_plan(999999, current_user_id=1, db=db_session)
        assert exc_info.value.error_code == ErrorCode.LOAD_PLAN_NOT_FOUND

    def test_approve_plan_already_approved_raises_exception(self, db_session, plan_with_placements):
        """Plan đã duyệt rồi -> PLAN_ALREADY_APPROVED (400)"""
        service = LoadPlanService()
        plan_with_placements.approved = True
        db_session.commit()

        with pytest.raises(AppException) as exc_info:
            service.approve_plan(plan_with_placements.id, current_user_id=1, db=db_session)
        assert exc_info.value.error_code == ErrorCode.PLAN_ALREADY_APPROVED

    def test_approve_plan_has_no_placements_raises_exception(self, db_session):
        """Plan không có kiện hàng nào được xếp -> PLAN_HAS_NO_PLACEMENTS (400)"""
        service = LoadPlanService()
        job = db_session.query(OptimizationJob).first()
        empty_plan = LoadPlan(job_id=job.id, plan_name="Empty Plan", packed_items_count=0, approved=False)
        db_session.add(empty_plan)
        db_session.commit()

        with pytest.raises(AppException) as exc_info:
            service.approve_plan(empty_plan.id, current_user_id=1, db=db_session)
        assert exc_info.value.error_code == ErrorCode.PLAN_HAS_NO_PLACEMENTS

    def test_approve_plan_invalid_lifo_duplicate_step_sequence_raises_exception(self, db_session):
        """Trùng thứ tự step_sequence (vi phạm LIFO) -> PLAN_LIFO_INVALID (400)"""
        service = LoadPlanService()
        job = db_session.query(OptimizationJob).first()
        plan = LoadPlan(job_id=job.id, plan_name="Invalid LIFO Plan", packed_items_count=2, approved=False)
        db_session.add(plan)
        db_session.commit()

        # Cả 2 placement đều có step_sequence=1 (duplicate!)
        p1 = PackagePlacement(load_plan_id=plan.id, package_id=1, step_sequence=1)
        p2 = PackagePlacement(load_plan_id=plan.id, package_id=2, step_sequence=1)
        db_session.add_all([p1, p2])
        db_session.commit()

        with pytest.raises(AppException) as exc_info:
            service.approve_plan(plan.id, current_user_id=1, db=db_session)
        assert exc_info.value.error_code == ErrorCode.PLAN_LIFO_INVALID


class TestLoadPlanApproveApi:
    def test_approve_plan_without_token_returns_401(self, db_session, plan_with_placements):
        """Không có token -> 401 Unauthorized"""
        app.dependency_overrides[get_db] = lambda: db_session
        client = TestClient(app)

        response = client.post(f"/api/v1/load-plans/{plan_with_placements.id}/approve")
        assert response.status_code == 401
        app.dependency_overrides.clear()

    def test_approve_plan_unauthorized_role_returns_403(self, db_session, plan_with_placements):
        """Role DRIVER không được phép duyệt plan -> 403 Forbidden"""
        app.dependency_overrides[get_db] = lambda: db_session
        client = TestClient(app)
        driver_token = make_jwt_token(["DRIVER"])

        response = client.post(
            f"/api/v1/load-plans/{plan_with_placements.id}/approve",
            headers={"Authorization": f"Bearer {driver_token}"},
        )
        assert response.status_code == 403
        app.dependency_overrides.clear()

    def test_approve_plan_dispatcher_role_returns_200_and_approved_plan(self, db_session, plan_with_placements):
        """Role DISPATCHER duyệt plan hợp lệ -> 200 OK + LoadPlanResponse với approved=True"""
        app.dependency_overrides[get_db] = lambda: db_session
        client = TestClient(app)
        dispatcher_token = make_jwt_token(["DISPATCHER"], user_id=456)

        response = client.post(
            f"/api/v1/load-plans/{plan_with_placements.id}/approve",
            headers={"Authorization": f"Bearer {dispatcher_token}"},
        )
        assert response.status_code == 200
        data = response.json()
        assert data["success"] is True
        plan_data = data["data"]
        assert plan_data["id"] == plan_with_placements.id
        assert plan_data["approved"] is True
        assert plan_data["approved_by_id"] == 456
        assert len(plan_data["placements"]) == 2

        app.dependency_overrides.clear()


class TestLoadPlanApprovalSchemaV34:
    def test_approve_plan_sets_approved_at_and_schema_v34_fields(self, db_session, plan_with_placements):
        """
        Acceptance Criteria 3:
          Set is_approved = True, approved_at = now(), approved_by_user_id = current_user.id
        """
        service = LoadPlanService()
        user_id = 999

        approved_plan = service.approve_plan(
            plan_with_placements.id,
            current_user_id=user_id,
            db=db_session,
        )

        assert approved_plan.is_approved is True
        assert approved_plan.approved_by_user_id == user_id
        assert approved_plan.approved_at is not None

        # Kiểm tra to_response
        response_dto = service.to_response(approved_plan, db_session)
        assert response_dto.is_approved is True
        assert response_dto.approved_by_user_id == user_id
        assert response_dto.approved_at is not None
        assert response_dto.job_id == approved_plan.job_id

    def test_approve_plan_validates_lifo_with_loading_sequence_duplicates(self, db_session):
        """
        Acceptance Criteria 2:
          Verify LIFO valid (loading_sequence không bị duplicate)
        """
        service = LoadPlanService()
        job = db_session.query(OptimizationJob).first()
        plan = LoadPlan(job_id=job.id, plan_name="Duplicate Loading Sequence Plan", approved=False)
        db_session.add(plan)
        db_session.commit()

        # Tạo placements với loading_sequence bị trùng lặp
        p1 = PackagePlacement(load_plan_id=plan.id, package_id=1, step_sequence=2)
        p2 = PackagePlacement(load_plan_id=plan.id, package_id=2, step_sequence=2)
        p1.loading_sequence = 2
        p2.loading_sequence = 2
        db_session.add_all([p1, p2])
        db_session.commit()

        with pytest.raises(AppException) as exc_info:
            service.approve_plan(plan.id, current_user_id=1, db=db_session)
        assert exc_info.value.error_code == ErrorCode.PLAN_LIFO_INVALID

    def test_api_approve_plan_returns_schema_v34_payload(self, db_session, plan_with_placements):
        """
        Acceptance Criteria 1, 3, 5:
          POST /api/v1/load-plans/{id}/approve trả 200 OK + LoadPlanResponse với is_approved, approved_at, approved_by_user_id
        """
        app.dependency_overrides[get_db] = lambda: db_session
        client = TestClient(app)
        dispatcher_token = make_jwt_token(["DISPATCHER"], user_id=789)

        response = client.post(
            f"/api/v1/load-plans/{plan_with_placements.id}/approve",
            headers={"Authorization": f"Bearer {dispatcher_token}"},
        )
        assert response.status_code == 200
        data = response.json()
        assert data["success"] is True
        plan_data = data["data"]
        assert plan_data["is_approved"] is True
        assert plan_data["approved_by_user_id"] == 789
        assert plan_data["approved_at"] is not None
        assert plan_data["job_id"] is not None

        app.dependency_overrides.clear()

