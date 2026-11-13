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
from app.entity.trip_model import AppUser, Trip
from app.exception.app_exception import AppException
from app.exception.error_code import ErrorCode
from app.service.optimize.load_plan_service import LoadPlanService


from tests.keycloak_tokens import service_headers, user_headers

DISPATCHER_SUB = "kc-dispatcher-approve"


def _dispatcher_of_the_trip(db_session, user_id=456, company_id=3):
    """Điều phối viên có tài khoản (users.keycloak_id = sub) thuộc công ty của chuyến."""
    db_session.query(Trip).first().company_id = company_id
    db_session.add(AppUser(id=user_id, keycloak_id=DISPATCHER_SUB, company_id=company_id))
    db_session.commit()


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

    trip = Trip(id=1, status="PLANNED")
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
    def test_approving_another_plan_of_the_trip_unapproves_the_old_one(self, db_session, plan_with_placements):
        """Mỗi chuyến một phương án đã duyệt: duyệt phương án B của cùng chuyến thì gỡ duyệt A."""
        plan_with_placements.approved = True
        other_job = OptimizationJob(job_uuid=str(uuid.uuid4()), trip_id=1, status=OptimizationJobStatus.COMPLETED.value)
        db_session.add(other_job)
        db_session.commit()
        plan_b = LoadPlan(job_id=other_job.id, plan_name="B", packed_items_count=1, approved=False)
        db_session.add(plan_b)
        db_session.commit()
        db_session.add(PackagePlacement(load_plan_id=plan_b.id, package_id=1, step_sequence=1, pos_x=0, pos_y=0, pos_z=0))
        db_session.commit()

        LoadPlanService().approve_plan(plan_b.id, current_user_id=5, db=db_session)

        db_session.expire_all()
        approved = [p.id for p in db_session.query(LoadPlan).filter(LoadPlan.approved.is_(True))]
        assert approved == [plan_b.id]

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

    @pytest.mark.usefixtures("keycloak_jwks")
    def test_approve_plan_unauthorized_role_returns_403(self, db_session, plan_with_placements):
        """Role DRIVER không được phép duyệt plan -> 403 Forbidden"""
        app.dependency_overrides[get_db] = lambda: db_session
        client = TestClient(app)

        response = client.post(
            f"/api/v1/load-plans/{plan_with_placements.id}/approve",
            headers=user_headers("DRIVER"),
        )
        assert response.status_code == 403
        app.dependency_overrides.clear()

    @pytest.mark.usefixtures("keycloak_jwks")
    def test_approve_plan_dispatcher_role_returns_200_and_approved_plan(self, db_session, plan_with_placements):
        """Role DISPATCHER duyệt plan hợp lệ -> 200 OK + LoadPlanResponse với approved=True"""
        _dispatcher_of_the_trip(db_session, user_id=456)
        app.dependency_overrides[get_db] = lambda: db_session
        client = TestClient(app)

        response = client.post(
            f"/api/v1/load-plans/{plan_with_placements.id}/approve",
            headers=user_headers("DISPATCHER", sub=DISPATCHER_SUB),
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


    @pytest.mark.usefixtures("keycloak_jwks")
    def test_approve_plan_of_another_company_returns_404(self, db_session, plan_with_placements):
        """Điều phối viên công ty khác không thấy phương án -> 404, phương án vẫn chưa duyệt."""
        _dispatcher_of_the_trip(db_session, company_id=3)
        db_session.query(Trip).first().company_id = 4
        db_session.commit()
        app.dependency_overrides[get_db] = lambda: db_session
        client = TestClient(app)

        response = client.post(
            f"/api/v1/load-plans/{plan_with_placements.id}/approve",
            headers=user_headers("DISPATCHER", sub=DISPATCHER_SUB),
        )
        assert response.status_code == 404
        db_session.refresh(plan_with_placements)
        assert plan_with_placements.approved is False
        app.dependency_overrides.clear()

    @pytest.mark.usefixtures("keycloak_jwks")
    def test_approve_plan_with_service_token_uses_the_approver_in_the_body(self, db_session, plan_with_placements):
        """Service token của LoadMasterService: người duyệt lấy từ thân (Java đã kiểm vai trò và công ty)."""
        app.dependency_overrides[get_db] = lambda: db_session
        client = TestClient(app)

        response = client.post(
            f"/api/v1/load-plans/{plan_with_placements.id}/approve",
            json={"approved_by_user_id": 789},
            headers=service_headers(),
        )
        assert response.status_code == 200
        assert response.json()["data"]["approved_by_id"] == 789
        app.dependency_overrides.clear()


class TestApprovalAuditCompany:
    """Nhật ký PLAN_APPROVED mang công ty của job, job thiếu thì của chuyến."""

    def _approve_and_read_log(self, db_session, plan):
        LoadPlanService().approve_plan(plan.id, current_user_id=7, db=db_session)
        return db_session.query(AuditLog).filter(AuditLog.action_type == "PLAN_APPROVED").one()

    def test_company_of_the_job(self, db_session, plan_with_placements):
        job = db_session.query(OptimizationJob).first()
        job.company_id = "12"
        db_session.commit()
        assert self._approve_and_read_log(db_session, plan_with_placements).company_id == 12

    def test_falls_back_to_company_of_the_trip(self, db_session, plan_with_placements):
        trip = db_session.query(Trip).first()
        trip.company_id = 34
        db_session.commit()
        assert self._approve_and_read_log(db_session, plan_with_placements).company_id == 34

    def test_no_company_known_stays_null(self, db_session, plan_with_placements):
        assert self._approve_and_read_log(db_session, plan_with_placements).company_id is None
