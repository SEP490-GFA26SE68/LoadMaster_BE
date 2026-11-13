"""Giờ ghi vào DB dùng chung: giờ Việt Nam không múi giờ, như Java."""
from datetime import datetime, timedelta, timezone

from app.config.clock import vietnam_now
from app.entity.common.audit_log import AuditLog
from app.entity.optimization.optimization_job import OptimizationJob


def test_vietnam_now_is_naive_utc_plus_seven():
    before = datetime.now(timezone.utc).replace(tzinfo=None) + timedelta(hours=7)
    now = vietnam_now()
    after = datetime.now(timezone.utc).replace(tzinfo=None) + timedelta(hours=7)

    assert now.tzinfo is None
    assert before - timedelta(seconds=1) <= now <= after + timedelta(seconds=1)


def test_shared_tables_default_to_vietnam_time():
    for entity in (AuditLog, OptimizationJob):
        default = entity.__table__.c.created_at.default
        assert default.arg.__name__ == vietnam_now.__name__
