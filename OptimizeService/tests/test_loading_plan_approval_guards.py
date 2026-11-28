"""Real SQLite approval/HTTP guards; PostgreSQL locking semantics need separate verification."""
import pytest
from sqlalchemy import create_engine, text, event
from sqlalchemy.orm import Session
from sqlalchemy.pool import StaticPool
from sqlalchemy.dialects import postgresql
from starlette.testclient import TestClient
from app.config.database import Base, get_db
from app.entity.trip_model import Trip, AppUser
from app.entity.optimization.optimization_job import OptimizationJob
from app.entity.optimization.load_plan import LoadPlan
from app.entity.optimization.package_placement import PackagePlacement
from app.entity.common.audit_log import AuditLog
from app.service.optimize.load_plan_service import LoadPlanService
from app.exception.app_exception import AppException
from app.main import app
from tests.optimization_legacy_fixtures import restored_overrides
from app.service.auth import auth_dependency
from tests.keycloak_tokens import FakeJwksClient
from tests.keycloak_tokens import user_headers


@pytest.fixture
def approval_db():
    engine = create_engine('sqlite:///:memory:', poolclass=StaticPool,
                           connect_args={'check_same_thread': False})
    Base.metadata.create_all(engine)
    with Session(engine, expire_on_commit=False) as db:
        if 'stale' not in LoadPlan.__mapper__.attrs:
            db.execute(text('ALTER TABLE load_plans ADD COLUMN is_stale BOOLEAN DEFAULT FALSE'))
        db.add_all([Trip(id=1, company_id=1, status='PLANNED'),
                    AppUser(id=10, company_id=1, keycloak_id='b5-dispatcher'),
                    AppUser(id=20, company_id=2, keycloak_id='b5-foreign')])
        db.add(OptimizationJob(id=1, trip_id=1, company_id='1', job_uuid='00000000-0000-0000-0000-000000000001', status='COMPLETED'))
        db.flush()
        db.add(LoadPlan(id=1, job_id=1, approved=False, packed_items_count=1))
        db.flush()
        db.add(PackagePlacement(id=1, load_plan_id=1, package_id=1, step_sequence=1))
        db.commit()
        yield db
    engine.dispose()


def reject_unchanged(db, expected):
    with pytest.raises(AppException) as error:
        LoadPlanService().approve_plan(1, 10, db)
    assert error.value.error_code.code == expected
    assert db.get(LoadPlan, 1).approved is False
    assert db.query(AuditLog).count() == 0


def test_stale_plan_is_rejected_from_current_database_value(approval_db):
    db = approval_db
    cached = db.get(LoadPlan, 1)  # Keep the ORM identity map populated before the database changes.
    db.execute(text('UPDATE load_plans SET is_stale=true WHERE id=1'))
    db.commit()
    assert cached.id == 1
    reject_unchanged(db, 'PLAN_STALE')


@pytest.mark.parametrize('status', ['DRAFT', 'LOADING', 'IN_TRANSIT', 'DELIVERED', 'CANCELLED', None])
def test_non_planned_trip_is_rejected(approval_db, status):
    db = approval_db
    db.get(Trip, 1).status = status
    db.commit()
    reject_unchanged(db, 'PLAN_TRIP_NOT_PLANNED')


def test_valid_approval_audit_and_old_plan_unapproval_are_committed(approval_db):
    db = approval_db
    db.add(LoadPlan(id=2, job_id=1, approved=True))
    db.commit()
    approved = LoadPlanService().approve_plan(1, 10, db)
    assert approved.approved and approved.approved_by_id == 10
    assert db.get(LoadPlan, 2).approved is False
    assert db.query(AuditLog).filter_by(action_type='PLAN_APPROVED').count() == 1


def test_approval_rechecks_plan_after_acquiring_trip_lock(approval_db):
    captured = []
    def collect(state):
        stmt = state.statement
        if getattr(stmt, '_for_update_arg', None) is not None:
            captured.append(str(stmt.compile(dialect=postgresql.dialect())))
    event.listen(approval_db, 'do_orm_execute', collect)
    try:
        LoadPlanService().approve_plan(1, 10, approval_db)
    finally:
        event.remove(approval_db, 'do_orm_execute', collect)
    assert len(captured) >= 2
    assert 'FROM trips' in captured[0] and 'FOR UPDATE' in captured[0]
    assert 'FROM load_plans' in captured[1] and 'FOR UPDATE' in captured[1]


@pytest.mark.parametrize('subject,status,code', [
    ('b5-foreign', 404, 'LOAD_PLAN_NOT_FOUND'),
    ('b5-dispatcher', 409, 'PLAN_STALE')])
def test_direct_user_http_approval_keeps_tenant_and_stale_guards(approval_db, monkeypatch, subject, status, code):
    db = approval_db
    monkeypatch.setattr(auth_dependency, "jwks_client", FakeJwksClient())
    db.execute(text('UPDATE load_plans SET is_stale=true WHERE id=1'))
    db.commit()
    with restored_overrides(app, {get_db: lambda: db}):
        client = TestClient(app)
        response = client.post('/api/v1/load-plans/1/approve',
            headers=user_headers('DISPATCHER', sub=subject))
    assert response.status_code == status
    assert response.json()['code'] == code
    assert db.get(LoadPlan, 1).approved is False


def test_company_guard_is_rechecked_in_the_locked_service(approval_db):
    with pytest.raises(AppException) as error:
        LoadPlanService().approve_plan(1, 20, approval_db, expected_company_id=2)
    assert error.value.error_code.code == 'LOAD_PLAN_NOT_FOUND'
    assert approval_db.get(LoadPlan, 1).approved is False
    assert approval_db.query(AuditLog).count() == 0


@pytest.mark.parametrize('role,http_status', [('DISPATCHER', 200), ('DRIVER', 403)])
def test_valid_http_identity_comes_from_token_not_body(approval_db, monkeypatch, role, http_status):
    monkeypatch.setattr(auth_dependency, 'jwks_client', FakeJwksClient())
    with restored_overrides(app, {get_db: lambda: approval_db}):
        response = TestClient(app).post('/api/v1/load-plans/1/approve',
            headers=user_headers(role, sub='b5-dispatcher'),
            json={'approved_by_user_id': 20, 'company_id': 2})
    assert response.status_code == http_status
    approved = approval_db.get(LoadPlan, 1)
    assert approved.approved is (http_status == 200)
    if http_status == 200:
        assert approved.approved_by_id == 10


def test_audit_write_failure_can_roll_back_the_whole_approval(approval_db):
    def fail_audit(session, context, instances):
        if any(isinstance(row, AuditLog) for row in session.new):
            raise RuntimeError('synthetic audit failure')
    event.listen(approval_db, 'before_flush', fail_audit)
    try:
        with pytest.raises(RuntimeError, match='synthetic audit failure'):
            LoadPlanService().approve_plan(1, 10, approval_db)
        approval_db.rollback()  # Mirrors get_db() closing the failed request session.
    finally:
        event.remove(approval_db, 'before_flush', fail_audit)
    assert approval_db.get(LoadPlan, 1).approved is False
    assert approval_db.query(AuditLog).count() == 0


def test_job_trip_company_mismatch_is_rejected(approval_db):
    approval_db.get(OptimizationJob, 1).company_id = '99'
    approval_db.commit()
    reject_unchanged(approval_db, 'LOAD_PLAN_NOT_FOUND')


def test_immediate_retry_rejects_without_duplicate_audit(approval_db):
    s=LoadPlanService();s.approve_plan(1,10,approval_db)
    with pytest.raises(AppException) as e:s.approve_plan(1,10,approval_db)
    assert e.value.error_code.code=='PLAN_ALREADY_APPROVED'
    assert approval_db.query(AuditLog).count()==1

def test_reselection_after_another_approval_is_not_request_idempotent(approval_db):
    db=approval_db
    db.add(LoadPlan(id=2,job_id=1,approved=False,packed_items_count=1));db.flush()
    db.add(PackagePlacement(id=2,load_plan_id=2,package_id=1,step_sequence=1));db.commit()
    s=LoadPlanService();s.approve_plan(1,10,db);s.approve_plan(2,10,db);s.approve_plan(1,10,db)
    db.expire_all()
    assert db.get(LoadPlan,1).approved is True
    assert db.get(LoadPlan,2).approved is False
    assert db.query(AuditLog).count()==3

def test_refresh_failure_does_not_undo_successful_commit(approval_db,monkeypatch):
    db=approval_db
    def unavailable(*a,**k):raise RuntimeError('SYNTHETIC_REFRESH_FAILURE')
    with monkeypatch.context() as m:
        m.setattr(db,'refresh',unavailable)
        with pytest.raises(RuntimeError,match='SYNTHETIC_REFRESH_FAILURE'):
            LoadPlanService().approve_plan(1,10,db)
    db.rollback();db.expire_all()
    assert db.get(LoadPlan,1).approved is True
    assert db.query(AuditLog).count()==1
