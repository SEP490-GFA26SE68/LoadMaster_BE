"""Test-only snapshots of Spring admission; SQLite memory only, never runtime admission."""
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
from types import SimpleNamespace
from uuid import uuid4

import jwt
import pytest
from cryptography.hazmat.primitives.asymmetric import rsa
from sqlalchemy import text
from app.entity.optimization.optimization_job import OptimizationJob


def _memory_only(db):
    url = db.get_bind().url
    assert url.get_backend_name() == 'sqlite' and url.database == ':memory:', 'Fixture must never write a real DB'


def seed_admission_catalog(db, trip):
    """Minimal authoritative tables absent from Python ORM; deliberately no production service call."""
    _memory_only(db)
    company_id = trip.company_id or 1
    trip.company_id = company_id
    statements = [
        "CREATE TABLE IF NOT EXISTS companies (id INTEGER PRIMARY KEY, status TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS subscription_plans (id INTEGER PRIMARY KEY, tier TEXT NOT NULL, algorithm_tier TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS company_subscriptions (id INTEGER PRIMARY KEY,company_id INTEGER REFERENCES companies(id),plan_id INTEGER REFERENCES subscription_plans(id),status TEXT NOT NULL,started_at TEXT NOT NULL,expires_at TEXT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS credit_accounts (id INTEGER PRIMARY KEY,company_id INTEGER UNIQUE REFERENCES companies(id),balance INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS credit_transactions (id INTEGER PRIMARY KEY,credit_account_id INTEGER NOT NULL REFERENCES credit_accounts(id),type TEXT NOT NULL,amount INTEGER NOT NULL,reference TEXT NOT NULL,refunded BOOLEAN NOT NULL DEFAULT false)",
        "CREATE UNIQUE INDEX IF NOT EXISTS fixture_usage_unique ON credit_transactions(reference) WHERE type='USAGE'",
    ]
    for statement in statements:
        db.execute(text(statement))
    db.execute(text("INSERT OR IGNORE INTO companies VALUES(:company,'ACTIVE')"), {'company': company_id})
    db.execute(text("INSERT OR IGNORE INTO subscription_plans VALUES(1,'BASIC','EP_DBLF')"))
    db.execute(text("INSERT OR IGNORE INTO company_subscriptions VALUES(:company,:company,1,'ACTIVE',:start,:end)"),
               {'company': company_id, 'start': (datetime.now(timezone.utc)-timedelta(days=1)).isoformat(),
                'end': (datetime.now(timezone.utc)+timedelta(days=30)).isoformat()})
    db.execute(text("INSERT OR IGNORE INTO credit_accounts VALUES(:company,:company,100)"), {'company': company_id})
    db.commit()


def spring_reserved_job(db, trip, *, objective='MAX_VOLUME', time_limit_sec=30, status='PENDING', tier='BASIC'):
    """Represents the committed job+USAGE snapshot Spring sends to the legacy V1 dispatcher."""
    seed_admission_catalog(db, trip)
    job = OptimizationJob(job_uuid=str(uuid4()), trip_id=trip.id, company_id=str(trip.company_id),
                          subscription_tier=tier, algorithm_tier=tier,
                          algorithm_name={'BASIC':'EP_DBLF','PRO':'EP_DBLF_GA','ULTIMATE':'EP_DBLF_GA_AI'}[tier],
                          objective=objective, time_limit_sec=time_limit_sec, status=status,
                          execution_generation=None)  # V1; V2 tests must use its ownership protocol.
    db.execute(text('UPDATE subscription_plans SET tier=:tier,algorithm_tier=:algorithm WHERE id=1'),
               {'tier':tier,'algorithm':job.algorithm_name})
    db.add(job)
    db.flush()
    db.execute(text("INSERT INTO credit_transactions(credit_account_id,type,amount,reference,refunded) VALUES(:company,'USAGE',-2,:reference,false)"),
               {'company': trip.company_id, 'reference': job.job_uuid})
    db.execute(text("UPDATE credit_accounts SET balance=balance-2 WHERE company_id=:company"), {'company': trip.company_id})
    db.commit()
    return job


def billing_snapshot(db):
    return (tuple(db.execute(text('SELECT id,company_id,balance FROM credit_accounts ORDER BY id')).all()),
            tuple(db.execute(text('SELECT credit_account_id,type,amount,reference,refunded FROM credit_transactions ORDER BY id')).all()))


@contextmanager
def restored_overrides(app, overrides):
    original = dict(app.dependency_overrides)
    app.dependency_overrides.update(overrides)
    try:
        yield
    finally:
        app.dependency_overrides.clear()
        app.dependency_overrides.update(original)


@pytest.fixture
def service_token(monkeypatch):
    """Local RS256/JWK only; real jwt.decode issuer/audience/time and FastAPI role/azp guards."""
    from app.service.auth import auth_dependency as auth
    from app.config.settings import settings
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    monkeypatch.setattr(auth.jwks_client, 'get_signing_key_from_jwt', lambda token: SimpleNamespace(key=key.public_key()))
    def issue(**changes):
        now = datetime.now(timezone.utc)
        claims = {'sub': 'synthetic-backend-service', 'azp': settings.KEYCLOAK_ALLOWED_CLIENT_ID,
                  'iss': settings.KEYCLOAK_ISSUER, 'aud': settings.KEYCLOAK_AUDIENCE,
                  'iat': now, 'exp': now+timedelta(minutes=5),
                  'resource_access': {settings.KEYCLOAK_AUDIENCE: {'roles': [settings.KEYCLOAK_REQUIRED_ROLE]}}}
        claims.update(changes)
        return jwt.encode(claims, key, algorithm='RS256')
    return issue


def make_offline_runner(**kwargs):
    from unittest.mock import Mock, AsyncMock
    from app.service.optimize.async_optimization_runner import AsyncOptimizationRunner
    from app.service.optimize.engine_exception_handler import EngineExceptionHandler
    credit = kwargs.pop('credit_client', None) or Mock(deduct_credit=AsyncMock(), refund_credit=AsyncMock())
    kwargs.setdefault('engine_exception_handler', EngineExceptionHandler(
        job_service=kwargs.get('job_service'), credit_client=credit, notification_service=Mock()))
    return AsyncOptimizationRunner(credit_client=credit, **kwargs)
