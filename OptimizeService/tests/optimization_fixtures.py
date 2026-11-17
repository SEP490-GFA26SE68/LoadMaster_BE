"""Current numeric IDs, cm and Spring-reserved/billed fixtures (test databases only)."""
import uuid,time
from types import SimpleNamespace
from unittest.mock import AsyncMock
from sqlalchemy import text
from app.entity.optimization.optimization_job import OptimizationJob
from app.entity.trip_model import Trip


def reserve_job(db,trip_id,config=None,**kw):
    """Emulate Spring's committed reservation; never calls retired Python admission."""
    tier=kw.pop('subscription_tier','BASIC')
    values=dict(job_uuid=str(uuid.uuid4()),trip_id=trip_id,company_id='1',subscription_tier=tier,
                algorithm_tier=tier,algorithm_name={'BASIC':'EP_DBLF','PRO':'EP_DBLF_GA','ULTIMATE':'EP_DBLF_GA_AI'}[tier],
                objective=getattr(config,'objective',None) or 'MAX_VOLUME',time_limit_sec=60,status='PENDING')
    values.update(kw)
    trip=db.get(Trip,trip_id);trip.company_id=1
    job=OptimizationJob(**values);db.add(job);db.flush()
    bill_job(db,job)
    return job


def bill_job(db,job):
    trip=db.get(Trip,job.trip_id);trip.company_id=1
    job.company_id='1';job.subscription_tier=job.subscription_tier or 'BASIC';job.algorithm_tier=job.subscription_tier
    for ddl in ['CREATE TABLE IF NOT EXISTS credit_accounts(id INTEGER PRIMARY KEY,company_id INTEGER)',
                'CREATE TABLE IF NOT EXISTS credit_transactions(reference TEXT UNIQUE,type TEXT,credit_account_id INTEGER,refunded BOOLEAN)']:
        db.execute(text(ddl))
    if not db.execute(text('SELECT 1 FROM credit_accounts WHERE id=1')).first():
        db.execute(text('INSERT INTO credit_accounts VALUES(1,1)'))
    db.execute(text("INSERT INTO credit_transactions VALUES(:ref,'USAGE',1,false)"),{'ref':job.job_uuid})
    db.commit()


def isolated_credit():
    return SimpleNamespace(deduct_credit=AsyncMock(),refund_credit=AsyncMock())


def token(roles,user_id=99,service=False):
    import jwt
    from app.config.settings import settings
    now=int(time.time())
    return jwt.encode(dict(sub=str(user_id),user_id=user_id,preferred_username='test-user',
        iss=settings.KEYCLOAK_ISSUER,aud=settings.KEYCLOAK_AUDIENCE,iat=now,exp=now+300,
        azp=settings.KEYCLOAK_ALLOWED_CLIENT_ID if service else 'test-web',
        resource_access={settings.KEYCLOAK_AUDIENCE:{'roles':roles}}),_KEY,algorithm='RS256')


from cryptography.hazmat.primitives.asymmetric import rsa
_KEY=rsa.generate_private_key(public_exponent=65537,key_size=2048)


def install_jwks(monkeypatch):
    from app.service.auth.auth_dependency import jwks_client
    monkeypatch.setattr(jwks_client,'get_signing_key_from_jwt',lambda _:SimpleNamespace(key=_KEY.public_key()))
