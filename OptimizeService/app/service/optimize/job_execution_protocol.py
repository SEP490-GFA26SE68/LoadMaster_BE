"""V2 durable ownership. All times come from PostgreSQL, never the HTTP client.

Legacy NULL metadata is never upgraded implicitly. No automatic same-UUID restart.
"""
from dataclasses import dataclass
from datetime import timedelta
from sqlalchemy import select, func
from fastapi import HTTPException
from app.entity.optimization.optimization_job import OptimizationJob
from app.exception.app_exception import AppException
from app.exception.error_code import ErrorCode
from app.service.optimize.authorized_job_dispatch import require_authorized_job

TERMINAL = {"COMPLETED", "SUCCESS", "PARTIAL", "FAILED", "TIMEOUT", "NO_SOLUTION"}

class OwnershipLost(RuntimeError):
    pass

@dataclass(frozen=True)
class Prepared:
    job_uuid: str
    generation: int
    status: str
    problem_json: str | None = None
    rejected: bool = False

class JobExecutionProtocol:
    def __init__(self, sessions, lease_seconds=30, heartbeat_seconds=5, grace_seconds=300):
        if not (0 < heartbeat_seconds < lease_seconds) or grace_seconds < 0:
            raise ValueError("Invalid lifecycle timing configuration")
        self.sessions = sessions
        self.lease = timedelta(seconds=lease_seconds)
        self.heartbeat_seconds = heartbeat_seconds
        self.grace_seconds = grace_seconds

    @staticmethod
    def locked(db, uuid):
        return db.execute(select(OptimizationJob).where(OptimizationJob.job_uuid == uuid)
                          .with_for_update().execution_options(populate_existing=True)).scalar_one()

    @staticmethod
    def now(db):
        return db.scalar(select(func.clock_timestamp()))

    def prepare(self, uuid, trip_id, builder, db):
        # JWT dependency has already run; authorize before entering prepare, then repeat
        # against the locked canonical row. This transaction contains no network/solver.
        require_authorized_job(uuid, trip_id, db, terminal_read=True)
        db.rollback()
        db.connection(execution_options={"isolation_level": "REPEATABLE READ"})
        try:
            job = self.locked(db, uuid)
            require_authorized_job(uuid, trip_id, db, terminal_read=True)
            if job.execution_generation is None:
                raise HTTPException(409, "Legacy job requires reconciliation; V2 cannot claim it")
            if job.status in TERMINAL:
                answer = Prepared(uuid, job.execution_generation, job.status,
                                  rejected=job.failure_code == "PACKING_PROFILE_ORIENTATION_INVALID")
            elif job.status == "RUNNING":
                # Even expired owners are never taken over by a duplicate request.
                answer = Prepared(uuid, job.execution_generation, job.status)
            elif job.status == "PENDING" and job.execution_generation == 0:
                now = self.now(db)
                if job.dispatch_expires_at is None or job.dispatch_expires_at <= now:
                    raise HTTPException(409, "Dispatch expired; check the existing job")
                try:
                    problem = builder(uuid, db)
                except AppException as exc:
                    if exc.error_code != ErrorCode.PACKING_PROFILE_ORIENTATION_INVALID:
                        raise
                    job.status = "FAILED"
                    job.failure_code = "PACKING_PROFILE_ORIENTATION_INVALID"
                    answer = Prepared(uuid, 0, "FAILED", rejected=True)
                else:
                    now = self.now(db)
                    if job.dispatch_expires_at <= now:
                        raise HTTPException(409, "Dispatch expired while building input; check the existing job")
                    job.execution_generation = 1
                    job.status = "RUNNING"
                    job.lease_expires_at = now + self.lease
                    # A serialized deep snapshot cannot be mutated by later DB edits.
                    answer = Prepared(uuid, 1, "RUNNING", problem.model_dump_json())
            else:
                raise HTTPException(409, "Job has no valid unowned V2 dispatch state")
            db.commit()  # Durable ownership/failure BEFORE scheduling or returning ACK/400.
            return answer
        except BaseException:
            db.rollback()
            raise

    def owner(self, db, uuid, generation):
        job = self.locked(db, uuid)
        now = self.now(db)
        if (job.execution_generation != generation or job.status != "RUNNING"
                or job.lease_expires_at is None or job.lease_expires_at <= now):
            raise OwnershipLost("Execution no longer owns this job")
        if (job.execution_started_at is not None and
                now >= job.execution_started_at + timedelta(seconds=(job.time_limit_sec or 60) + self.grace_seconds)):
            raise OwnershipLost("Absolute execution deadline exceeded")
        return job, now

    def begin(self, uuid, generation):
        with self.sessions() as db, db.begin():
            job, now = self.owner(db, uuid, generation)
            if job.execution_started_at is not None:
                return False
            job.execution_started_at = now
            return True

    def heartbeat(self, uuid, generation):
        with self.sessions() as db, db.begin():
            job, now = self.owner(db, uuid, generation)
            if job.execution_started_at is None:
                raise OwnershipLost("Worker has not begun")
            absolute = job.execution_started_at + timedelta(seconds=(job.time_limit_sec or 60) + self.grace_seconds)
            if now >= absolute:
                raise OwnershipLost("Absolute execution deadline exceeded")
            job.lease_expires_at = min(now + self.lease, absolute)

    def finalize(self, uuid, generation, status, write_result=None, failure_code=None, computation_ms=None):
        if status not in TERMINAL:
            raise ValueError("Finalize requires a terminal status")
        with self.sessions() as db, db.begin():
            job, _ = self.owner(db, uuid, generation)
            if job.execution_started_at is None:
                raise OwnershipLost("Worker has not begun")
            if write_result is not None:
                db.info["execution_fence"] = (uuid, generation)
                try:
                    write_result(db)
                finally:
                    db.info.pop("execution_fence", None)
            # Flush every pending result row before the final fence. Re-read the locked
            # canonical job and PostgreSQL clock: a live lease alone does not imply an
            # unexpired absolute execution budget. Any failure rolls back this entire
            # outer transaction, including result savepoints already released by save().
            db.flush()
            job, _ = self.owner(db, uuid, generation)
            if job.execution_started_at is None:
                raise OwnershipLost("Worker has not begun")
            job.status = status
            job.failure_code = failure_code
            job.computation_ms = computation_ms
            # No callback/HTTP and no unconditional status service inside this transaction.
