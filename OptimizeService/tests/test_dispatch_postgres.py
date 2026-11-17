"""Opt-in real PostgreSQL CAS test. Creates its OWN disposable Docker container.

Run with LOADMASTER_DISPATCH_POSTGRES_TEST=1. Never accepts an external database URL.
No application profiles, production DDL or migrations are loaded.
"""
import asyncio
import json
import os
import subprocess
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from threading import Barrier
from unittest.mock import AsyncMock, Mock

import pytest
from sqlalchemy import create_engine, text
from sqlalchemy.orm import Session

from app.service.optimize.async_optimization_runner import AsyncOptimizationRunner


@pytest.fixture
def isolated_postgres():
    if os.getenv("LOADMASTER_DISPATCH_POSTGRES_TEST") != "1":
        pytest.skip("Opt-in: needs Docker, always creates a private PostgreSQL container")
    name="loadmaster-dispatch-test-"+uuid.uuid4().hex
    password=uuid.uuid4().hex
    def docker(*args):
        return subprocess.check_output(["docker",*args],text=True,timeout=30).strip()
    docker("run","-d","--rm","--name",name,"-e","POSTGRES_DB=dispatch_test",
           "-e","POSTGRES_PASSWORD="+password,"-p","127.0.0.1::5432","postgres:17")
    engine=None
    try:
        port=json.loads(docker("inspect",name))[0]["NetworkSettings"]["Ports"]["5432/tcp"][0]["HostPort"]
        engine=create_engine(f"postgresql+psycopg2://postgres:{password}@127.0.0.1:{port}/dispatch_test")
        ready=False
        for _ in range(60):
            try:
                with engine.connect() as conn: conn.execute(text("SELECT 1"))
                ready=True;break
            except Exception: time.sleep(0.25)
        assert ready,"Private PostgreSQL did not start"
        with engine.begin() as conn:
            for ddl in SCHEMA.split(";"):
                if ddl.strip():conn.execute(text(ddl))
        yield engine
    finally:
        if engine is not None:engine.dispose()
        docker("stop",name)


def test_two_workers_racing_same_billed_job_only_one_enters_solver(isolated_postgres):
    engine=isolated_postgres
    barrier=Barrier(2)
    solver=Mock(solve=AsyncMock(return_value=object()))
    handler=Mock()
    def worker():
        with Session(engine) as db:
            runner=AsyncOptimizationRunner(optimization_client=solver,persistence_service=Mock(),
                engine_exception_handler=handler,credit_client=Mock())
            barrier.wait(timeout=10)
            asyncio.run(runner.run("reserved-pg-job",problem=object(),db=db))
    with ThreadPoolExecutor(max_workers=2) as pool:
        futures=[pool.submit(worker) for _ in range(2)]
        for future in futures:future.result(timeout=20)
    solver.solve.assert_awaited_once()
    handler.handle.assert_not_called()
    with engine.connect() as conn:
        assert conn.execute(text("SELECT status FROM optimization_jobs")).scalar()=="RUNNING"
        assert conn.execute(text("SELECT count(*) FROM optimization_jobs")).scalar()==1
        assert conn.execute(text("SELECT count(*) FROM credit_transactions")).scalar()==1


SCHEMA="""
CREATE TABLE trips(id BIGINT PRIMARY KEY,company_id BIGINT,vehicle_id BIGINT,
 trip_code VARCHAR(50),status VARCHAR(50),override_reason VARCHAR(255));
CREATE TABLE optimization_jobs(id BIGSERIAL PRIMARY KEY,trip_id BIGINT,job_uuid VARCHAR(64) UNIQUE,
 algorithm_name VARCHAR(50),objective VARCHAR(50),time_limit_sec INTEGER,status VARCHAR(50),
 computation_ms BIGINT,parent_plan_id BIGINT,seed INTEGER,company_id VARCHAR(64),
 subscription_tier VARCHAR(50),algorithm_tier VARCHAR(50),created_at TIMESTAMP);
CREATE TABLE credit_accounts(id BIGINT PRIMARY KEY,company_id BIGINT);
CREATE TABLE credit_transactions(reference VARCHAR(64),type VARCHAR(50),credit_account_id BIGINT,refunded BOOLEAN);
INSERT INTO trips(id,company_id) VALUES(7,1);
INSERT INTO optimization_jobs(trip_id,job_uuid,company_id,subscription_tier,algorithm_tier,status)
 VALUES(7,'reserved-pg-job','1','BASIC','BASIC','PENDING');
INSERT INTO credit_accounts VALUES(1,1);
INSERT INTO credit_transactions VALUES('reserved-pg-job','USAGE',1,false);
"""
