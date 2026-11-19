"""Dispatch only a Spring-persisted, billed INITIAL action. No prices or balance writes here."""
from sqlalchemy import text, update
from app.config.diagnostics import mark
from sqlalchemy.orm import Session

from app.constant.optimization.job_status import OptimizationJobStatus
from app.entity.optimization.optimization_job import OptimizationJob
from app.entity.trip_model import Trip
from app.exception.app_exception import AppException
from app.exception.error_code import ErrorCode


def require_authorized_job(job_uuid: str, trip_id: int, db: Session, terminal_read: bool = False) -> OptimizationJob:
    if not job_uuid:
        raise AppException(ErrorCode.INVALID_INPUT)
    mark("job_lookup_started", job_uuid=job_uuid, trip_id=trip_id)
    job = db.query(OptimizationJob).filter(OptimizationJob.job_uuid == job_uuid).first()
    if job is None:
        raise AppException(ErrorCode.OPTIMIZATION_JOB_NOT_FOUND)
    if job.status not in {state.value for state in OptimizationJobStatus}:
        raise AppException(ErrorCode.INVALID_INPUT)
    mark("job_located")
    mark("authorization_checks_started")
    trip = db.query(Trip).filter(Trip.id == trip_id).first()
    if (trip is None or job.trip_id != trip_id or not job.company_id
            or trip.company_id is None or str(trip.company_id) != str(job.company_id)
            or job.parent_plan_id is not None
            or job.subscription_tier not in {"BASIC", "PRO", "ULTIMATE"}
            or job.algorithm_tier != job.subscription_tier):
        raise AppException(ErrorCode.UNAUTHORIZED)
    # Read the authoritative Spring ledger, never calculate a price or update a balance.
    billed = db.execute(text("""
        SELECT 1 FROM credit_transactions tx
        JOIN credit_accounts ca ON ca.id = tx.credit_account_id
        WHERE tx.reference = :reference AND tx.type = 'USAGE'
          AND CAST(ca.company_id AS VARCHAR) = :company
          AND (tx.refunded = false OR :terminal_read)
    """), {"reference": job_uuid, "company": str(job.company_id),
         "terminal_read": terminal_read and job.status not in {"PENDING", "RUNNING"}}).first()
    if billed is None:
        raise AppException(ErrorCode.UNAUTHORIZED)
    return job


def claim_for_execution(job_uuid: str, trip_id: int, db: Session) -> bool:
    job = require_authorized_job(job_uuid, trip_id, db)
    if job.execution_generation is not None:
        raise RuntimeError("V1 cannot execute a V2 job")
    # PostgreSQL rechecks the predicate after waiting for a concurrent updater.
    changed = db.execute(update(OptimizationJob).where(
        OptimizationJob.job_uuid == job_uuid, OptimizationJob.status == "PENDING",
        OptimizationJob.execution_generation.is_(None)
    ).values(status="RUNNING")).rowcount
    db.commit()
    return changed == 1
