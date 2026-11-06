"""Controller contract: RS256 service JWT and an existing Spring-billed UUID.
No authentication dependency overrides; JWKS transport alone uses a local test key.
"""
import uuid
from unittest.mock import AsyncMock,Mock
import pytest
from sqlalchemy import create_engine,text
from sqlalchemy.orm import Session
from sqlalchemy.pool import StaticPool
from starlette.testclient import TestClient
from app.main import app
from app.config.database import Base,get_db
from app.config.settings import settings
from app.entity.trip_model import Trip
from app.entity.optimization.optimization_job import OptimizationJob
from app.controller.optimization.optimization_router import get_async_runner,get_load_plan_service
from app.dto.response.load_plan_response import LoadPlanResponse
from tests.optimization_fixtures import reserve_job,token,install_jwks


@pytest.fixture
def client(monkeypatch):
    install_jwks(monkeypatch)
    engine=create_engine("sqlite:///:memory:",connect_args={"check_same_thread":False},poolclass=StaticPool)
    Base.metadata.create_all(engine)
    with Session(engine) as db:
        db.add(Trip(id=1,company_id=1));db.commit()
        app.dependency_overrides[get_db]=lambda:db
        app.dependency_overrides[get_async_runner]=lambda:Mock(run=AsyncMock())
        with TestClient(app) as client:
            client.test_db=db
            yield client
    app.dependency_overrides.clear()
    engine.dispose()


@pytest.fixture
def dispatcher_headers():
    # Python is now called by the Spring service, not by a dispatcher user token.
    return {"Authorization":"Bearer "+token([settings.KEYCLOAK_REQUIRED_ROLE],service=True)}


@pytest.fixture
def admin_headers(dispatcher_headers):
    return dispatcher_headers


@pytest.fixture
def driver_headers():
    return {"Authorization":"Bearer "+token(["DRIVER"],service=True)}


class TestOptimizationAuth:
    def test_submit_job_without_token_returns_401(self,client):
        response=client.post("/api/v1/optimization/jobs",json={"trip_id":1,"job_uuid":str(uuid.uuid4())})
        assert response.status_code==401

    def test_submit_job_with_unauthorized_role_returns_403(self,client,driver_headers):
        response=client.post("/api/v1/optimization/jobs",json={"trip_id":1,"job_uuid":str(uuid.uuid4())},headers=driver_headers)
        assert response.status_code==403
        assert client.test_db.query(OptimizationJob).count()==0


class TestSubmitJob:
    def test_submit_job_success_returns_202_and_job_id(self,client,dispatcher_headers):
        job=reserve_job(client.test_db,1)
        response=client.post("/api/v1/optimization/jobs",json={"trip_id":1,"job_uuid":job.job_uuid},headers=dispatcher_headers)
        assert response.status_code==202
        body=response.json();assert body["success"] is True
        assert body["data"]["job_id"]==job.job_uuid
        assert client.test_db.query(OptimizationJob).count()==1
        assert client.test_db.execute(text("select count(*) from credit_transactions")).scalar()==1

    def test_submit_job_with_non_existent_trip_returns_404(self,client,admin_headers):
        # A non-existent trip has no server reservation. UUID lookup fails before execution.
        response=client.post("/api/v1/optimization/jobs",json={"trip_id":99999,"job_uuid":str(uuid.uuid4())},headers=admin_headers)
        assert response.status_code==404
        assert response.json()["code"]=="OPTIMIZATION_JOB_NOT_FOUND"
        assert client.test_db.query(OptimizationJob).count()==0


class TestGetJobStatus:
    def test_get_job_success_returns_200_and_status(self,client,dispatcher_headers):
        job=reserve_job(client.test_db,1,status="RUNNING")
        response=client.get(f"/api/v1/optimization/jobs/{job.job_uuid}",headers=dispatcher_headers)
        assert response.status_code==200
        body=response.json();assert body["success"] is True
        data=body["data"]
        assert data["job_uuid"]==job.job_uuid and data["status"]=="RUNNING" and data["trip_id"]==1

    def test_get_job_not_found_returns_404(self,client,dispatcher_headers):
        response=client.get(f"/api/v1/optimization/jobs/{uuid.uuid4()}",headers=dispatcher_headers)
        assert response.status_code==404
        assert response.json()["code"]=="OPTIMIZATION_JOB_NOT_FOUND"


class TestGetJobPlans:
    def test_get_job_plans_success_returns_200_and_plans(self,client,dispatcher_headers):
        # DTO seam unit test; real database plan read/write is tested separately.
        service=Mock();service.get_plans_for_job.return_value=[LoadPlanResponse(id=1,plan_name="Plan Optimal 1",
            packed_items_count=10,volume_utilization=.88,weight_utilization=.75,approved=False,placements=[])]
        app.dependency_overrides[get_load_plan_service]=lambda:service
        job_id=str(uuid.uuid4())
        response=client.get(f"/api/v1/optimization/jobs/{job_id}/plans",headers=dispatcher_headers)
        assert response.status_code==200
        body=response.json();assert body["success"] is True
        plans=body["data"]
        assert len(plans)==1 and plans[0]["plan_name"]=="Plan Optimal 1" and plans[0]["packed_items_count"]==10
        service.get_plans_for_job.assert_called_once_with(job_id,client.test_db)
