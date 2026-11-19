"""Old V1 writers cannot mutate a V2 job even from a stale pre-claim read."""
import uuid
import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from app.entity.trip_model import Base, Trip
from app.entity.optimization.optimization_job import OptimizationJob
from app.repository.optimization.optimization_job_repository import OptimizationJobRepository

@pytest.mark.parametrize("generation,status,allowed", [(None,"RUNNING",True),(None,"COMPLETED",False),(0,"PENDING",False),(1,"RUNNING",False),(2,"RUNNING",False)])
def test_v1_cas_is_generation_fenced(generation,status,allowed):
    engine=create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)
    try:
        with sessionmaker(bind=engine)() as db:
            db.add(Trip(id=1,status="PLANNED"))
            key=str(uuid.uuid4())
            db.add(OptimizationJob(job_uuid=key,trip_id=1,status=status,execution_generation=generation))
            db.commit()
            assert OptimizationJobRepository().transition_unfinished(key,"FAILED",1,db) is allowed
            db.commit()
            job=db.query(OptimizationJob).filter_by(job_uuid=key).one()
            assert job.status == ("FAILED" if allowed else status)
            assert job.execution_generation == generation
    finally:
        engine.dispose()
