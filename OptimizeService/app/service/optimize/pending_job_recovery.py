"""Resume Spring-reserved V1 jobs that stayed PENDING because HTTP dispatch never scheduled the worker."""
from __future__ import annotations

import asyncio
import logging
from datetime import timedelta

from sqlalchemy import text

from app.config.clock import vietnam_now
from app.config.database import SessionLocal
from app.entity.optimization.optimization_job import OptimizationJob
from app.service.optimize.authorized_job_dispatch import require_authorized_job

logger = logging.getLogger(__name__)

STALE_AFTER = timedelta(seconds=15)


def stale_pending_v1_uuids(db, *, now=None) -> list[str]:
    cutoff = (now or vietnam_now()) - STALE_AFTER
    rows = db.query(OptimizationJob).filter(
        OptimizationJob.status == "PENDING",
        OptimizationJob.execution_generation.is_(None),
        OptimizationJob.created_at <= cutoff,
    ).order_by(OptimizationJob.id).limit(5).all()
    ready = []
    for job in rows:
        billed = db.execute(text("""
            SELECT 1 FROM credit_transactions tx
            JOIN credit_accounts ca ON ca.id = tx.credit_account_id
            WHERE tx.reference = :reference AND tx.type = 'USAGE'
              AND CAST(ca.company_id AS VARCHAR) = :company
              AND tx.refunded = false
        """), {"reference": job.job_uuid, "company": str(job.company_id)}).first()
        if billed is None:
            continue
        try:
            require_authorized_job(job.job_uuid, job.trip_id, db)
        except Exception:
            continue
        ready.append(job.job_uuid)
    return ready


async def recover_stale_pending_jobs(runner) -> int:
    with SessionLocal() as db:
        uuids = stale_pending_v1_uuids(db)
    for uuid in uuids:
        logger.warning("Recovering stale PENDING optimization job %s", uuid)
        await runner.run(uuid)
    return len(uuids)


async def recovery_loop(runner, interval_sec: float = 10.0):
    while True:
        try:
            await recover_stale_pending_jobs(runner)
        except Exception:
            logger.exception("Pending optimization job recovery failed")
        await asyncio.sleep(interval_sec)
