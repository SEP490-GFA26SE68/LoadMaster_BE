"""Optimization V1 router consumes reserved UUIDs and service RS256 JWTs; no Python admission."""
from unittest.mock import AsyncMock,MagicMock,ANY
from uuid import uuid4
import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool
from starlette.testclient import TestClient
from app.main import app
from app.config.database import Base,get_db
from app.entity.trip_model import Trip
from app.entity.optimization.optimization_job import OptimizationJob
from app.controller.optimization.optimization_router import get_async_runner,get_load_plan_service
from app.dto.response.load_plan_response import LoadPlanResponse
from tests.optimization_legacy_fixtures import spring_reserved_job,service_token,restored_overrides,billing_snapshot

@pytest.fixture
def router_db():
    engine=create_engine('sqlite:///:memory:',poolclass=StaticPool,connect_args={'check_same_thread':False})
    Base.metadata.create_all(engine)
    with sessionmaker(bind=engine)() as db:
        db.add(Trip(id=101,company_id=1,status='PLANNED'));db.commit()
        yield db
    engine.dispose()

@pytest.fixture
def runner():
    return MagicMock(run=AsyncMock())

@pytest.fixture
def client(router_db,runner):
    with restored_overrides(app,{get_db:lambda:router_db,get_async_runner:lambda:runner}),TestClient(app) as client:
        yield client

@pytest.fixture
def service_headers(service_token):
    return {'Authorization':'Bearer '+service_token()}

class TestOptimizationAuth:
    def test_submit_job_without_token_returns_401(self,client):
        response=client.post('/api/v1/optimization/jobs',json={'trip_id':101,'job_uuid':str(uuid4())})
        assert response.status_code==401

    def test_submit_job_with_unauthorized_role_returns_403(self,client,service_token):
        from app.config.settings import settings
        token=service_token(resource_access={settings.KEYCLOAK_AUDIENCE:{'roles':['DRIVER']}})
        response=client.post('/api/v1/optimization/jobs',json={'trip_id':101,'job_uuid':str(uuid4())},headers={'Authorization':'Bearer '+token})
        assert response.status_code==403

class TestSubmitJob:
    def test_submit_job_success_returns_202_and_job_id(self,client,service_headers,router_db,runner):
        job=spring_reserved_job(router_db,router_db.get(Trip,101))
        before=billing_snapshot(router_db)
        response=client.post('/api/v1/optimization/jobs',json={'trip_id':101,'job_uuid':job.job_uuid},headers=service_headers)
        assert response.status_code==202
        assert response.json()['success'] is True
        assert response.json()['data']['job_id']==job.job_uuid
        runner.run.assert_awaited_once_with(job.job_uuid)
        assert router_db.query(OptimizationJob).count()==1
        assert billing_snapshot(router_db)==before

    def test_submit_unknown_reserved_job_returns_404(self,client,service_headers,runner):
        response=client.post('/api/v1/optimization/jobs',json={'trip_id':999,'job_uuid':str(uuid4())},headers=service_headers)
        assert response.status_code==404
        assert response.json()['code']=='OPTIMIZATION_JOB_NOT_FOUND'
        runner.run.assert_not_awaited()

    def test_trip_mismatch_cannot_use_reserved_job(self,client,service_headers,router_db,runner):
        job=spring_reserved_job(router_db,router_db.get(Trip,101))
        before=billing_snapshot(router_db)
        response=client.post('/api/v1/optimization/jobs',json={'trip_id':999,'job_uuid':job.job_uuid},headers=service_headers)
        assert response.status_code==403
        runner.run.assert_not_awaited()
        assert billing_snapshot(router_db)==before

class TestGetJobStatus:
    def test_get_job_success_returns_200_and_status(self,client,service_headers,router_db):
        job=spring_reserved_job(router_db,router_db.get(Trip,101),status='RUNNING')
        response=client.get(f'/api/v1/optimization/jobs/{job.job_uuid}',headers=service_headers)
        assert response.status_code==200
        data=response.json()['data']
        assert data['job_uuid']==job.job_uuid
        assert data['status']=='RUNNING'
        assert data['trip_id']==101  # Current numeric trip contract.

    def test_get_job_not_found_returns_404(self,client,service_headers):
        response=client.get('/api/v1/optimization/jobs/'+str(uuid4()),headers=service_headers)
        assert response.status_code==404
        assert response.json()['code']=='OPTIMIZATION_JOB_NOT_FOUND'

class TestGetJobPlans:
    def test_get_job_plans_success_returns_200_and_plans(self,client,service_headers):
        # DTO serialization seam, not a synthetic claim that these ten packages were solved.
        service=MagicMock()
        service.get_plans_for_job.return_value=[LoadPlanResponse(id=1,plan_name='Plan Optimal 1',packed_items_count=10,
            volume_utilization=0.88,weight_utilization=0.75,approved=False,placements=[])]
        uuid=str(uuid4())
        with restored_overrides(app,{get_load_plan_service:lambda:service}):
            response=client.get(f'/api/v1/optimization/jobs/{uuid}/plans',headers=service_headers)
        assert response.status_code==200
        assert response.json()['success'] is True
        plans=response.json()['data']
        assert len(plans)==1
        assert plans[0]['plan_name']=='Plan Optimal 1'
        assert plans[0]['packed_items_count']==10
        service.get_plans_for_job.assert_called_once_with(uuid,ANY)
