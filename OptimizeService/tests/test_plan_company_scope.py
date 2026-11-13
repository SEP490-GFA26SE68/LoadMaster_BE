"""
So sánh và duyệt phương án bằng token người dùng chỉ trong công ty của người gọi.

Token Keycloak không mang công ty: dịch vụ tra users theo keycloak_id (= sub).
Phương án công ty khác, hay người gọi không thuộc công ty nào: LOAD_PLAN_NOT_FOUND (404), không lộ dữ liệu.
Service token của Spring Boot không bị kiểm (Java đã kiểm công ty).
"""
import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool
from starlette.testclient import TestClient

from app.config.database import Base, get_db
from app.config.settings import settings
from app.entity.optimization.load_plan import LoadPlan
from app.entity.optimization.optimization_job import OptimizationJob
from app.entity.optimization.package_placement import PackagePlacement
from app.entity.trip_model import AppUser, Trip
from app.main import app
from app.service.auth import auth_dependency

DISPATCHER_A = {
    "sub": "kc-dispatcher-a",
    "azp": "loadmaster-web",
    "resource_access": {settings.KEYCLOAK_AUDIENCE: {"roles": ["DISPATCHER"]}},
}
DISPATCHER_B = {**DISPATCHER_A, "sub": "kc-dispatcher-b"}
NO_COMPANY = {**DISPATCHER_A, "sub": "kc-platform-admin"}


@pytest.fixture
def db():
    engine = create_engine(
        "sqlite:///:memory:",
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    Base.metadata.create_all(engine)
    session = sessionmaker(autocommit=False, autoflush=False, bind=engine)()
    session.add_all([
        AppUser(id=1, keycloak_id="kc-dispatcher-a", company_id=10),
        AppUser(id=2, keycloak_id="kc-dispatcher-b", company_id=20),
        AppUser(id=3, keycloak_id="kc-platform-admin", company_id=None),
        Trip(id=1, status="PLANNED", company_id=10),
        Trip(id=2, status="PLANNED", company_id=20),
    ])
    session.commit()
    session.add_all([
        OptimizationJob(id=1, trip_id=1, job_uuid="job-a", status="COMPLETED", company_id="10"),
        OptimizationJob(id=2, trip_id=2, job_uuid="job-b", status="COMPLETED", company_id="20"),
    ])
    session.commit()
    session.add_all([
        LoadPlan(id=11, job_id=1, plan_name="A1", packed_items_count=1, approved=False),
        LoadPlan(id=12, job_id=1, plan_name="A2", packed_items_count=1, approved=False),
        LoadPlan(id=21, job_id=2, plan_name="B1", packed_items_count=1, approved=False),
    ])
    session.commit()
    session.add_all([
        PackagePlacement(load_plan_id=plan_id, package_id=1, pos_x=0, pos_y=0, pos_z=0,
                         packed_length=10, packed_width=10, packed_height=10,
                         rotation_type=0, step_sequence=1)
        for plan_id in (11, 12, 21)
    ])
    session.commit()
    yield session
    session.close()


@pytest.fixture
def client(db, monkeypatch):
    calls = {}

    def fake_decode(token):
        return calls["claims"]

    monkeypatch.setattr(auth_dependency, "decode_token", fake_decode)
    app.dependency_overrides[get_db] = lambda: db
    test_client = TestClient(app)

    def call(claims, method, url):
        calls["claims"] = claims
        return test_client.request(method, url, headers={"Authorization": "Bearer t"})

    yield call
    app.dependency_overrides.clear()


class TestCompareScope:
    def test_same_company_compares(self, client):
        response = client(DISPATCHER_A, "GET", "/api/v1/load-plans/compare?planId1=11&planId2=12")
        assert response.status_code == 200
        assert response.json()["data"]["plan1Id"] == 11

    def test_plan_of_other_company_is_not_found(self, client):
        response = client(DISPATCHER_A, "GET", "/api/v1/load-plans/compare?planId1=11&planId2=21")
        assert response.status_code == 404
        assert response.json()["code"] == "LOAD_PLAN_NOT_FOUND"

    def test_caller_without_company_is_not_found(self, client):
        response = client(NO_COMPANY, "GET", "/api/v1/load-plans/compare?planId1=11&planId2=12")
        assert response.status_code == 404


class TestApproveScope:
    def test_user_of_other_company_cannot_approve(self, client, db):
        response = client(DISPATCHER_B, "POST", "/api/v1/load-plans/11/approve")
        assert response.status_code == 404
        assert db.get(LoadPlan, 11).approved is False

    def test_user_of_same_company_approves(self, client, db):
        response = client(DISPATCHER_A, "POST", "/api/v1/load-plans/11/approve")
        assert response.status_code == 200
        db.expire_all()
        assert db.get(LoadPlan, 11).approved is True
