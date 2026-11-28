"""Approval locking/atomicity against a self-created disposable PostgreSQL only."""
import json
import re
from pathlib import Path
import socket
import subprocess
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from threading import Event

import pytest
from sqlalchemy import create_engine, event, text
from sqlalchemy.orm import Session
from app.config.database import Base
from app.entity.trip_model import Trip, AppUser, TransportOrder, CargoPackage, DeliveryStop
from app.entity.optimization.optimization_job import OptimizationJob
from app.entity.optimization.load_plan import LoadPlan
from app.entity.optimization.package_placement import PackagePlacement
from app.entity.common.audit_log import AuditLog
from app.service.optimize.load_plan_service import LoadPlanService
from app.exception.app_exception import AppException

pytestmark = pytest.mark.integration


def docker(*args):
    return subprocess.check_output(['docker', *args], text=True, timeout=45).strip()


@pytest.fixture(scope='module')
def disposable_pg():
    name = 'loadmaster-b5-review-' + uuid.uuid4().hex[:12]
    password = uuid.uuid4().hex
    docker('run', '-d', '--rm', '--name', name, '-e', 'POSTGRES_DB=b5_disposable',
           '-e', 'POSTGRES_PASSWORD=' + password, '-p', '127.0.0.1::5432', 'postgres:17')
    engine = None
    previous_connect = socket.socket.connect
    try:
        info = json.loads(docker('inspect', name))[0]
        assert info['Config']['Image'] == 'postgres:17'
        binding = info['NetworkSettings']['Ports']['5432/tcp'][0]
        assert binding['HostIp'] == '127.0.0.1'
        port = int(binding['HostPort'])
        # The offline bootstrap denies every socket except this container's verified binding.
        import __main__
        raw_connect = getattr(__main__, 'original', previous_connect)
        def pg_only(sock, address):
            if isinstance(address, tuple) and address[:2] == ('127.0.0.1', port):
                return raw_connect(sock, address)
            return previous_connect(sock, address)
        socket.socket.connect = pg_only
        engine = create_engine(f'postgresql+psycopg2://postgres:{password}@127.0.0.1:{port}/b5_disposable',
                               connect_args={'connect_timeout': 3}, pool_size=5, max_overflow=0)
        for attempt in range(80):
            try:
                with engine.connect() as conn:
                    assert conn.execute(text('select current_database()')).scalar_one() == 'b5_disposable'
                break
            except Exception:
                if attempt == 79:
                    raise
                time.sleep(.25)
        Base.metadata.create_all(engine)
        yield engine
    finally:
        socket.socket.connect = previous_connect
        if engine is not None:
            engine.dispose()
        docker('stop', name)


@pytest.fixture
def pg(disposable_pg):
    engine = disposable_pg
    with engine.begin() as conn:
        for table in reversed(Base.metadata.sorted_tables):
            conn.execute(table.delete())
    with Session(engine) as db:
        db.add_all([Trip(id=1, company_id=1, status='PLANNED'), AppUser(id=10, company_id=1)])
        db.add(OptimizationJob(id=1, trip_id=1, company_id='1', job_uuid=str(uuid.uuid4()), status='COMPLETED'))
        db.flush()
        db.add(DeliveryStop(id=1, trip_id=1, stop_sequence=1))
        db.flush()
        db.add(TransportOrder(id=1, delivery_stop_id=1))
        db.flush()
        db.add_all([CargoPackage(id=i, order_id=1, actual_length=10, actual_width=10, actual_height=10) for i in (1, 2)])
        db.add_all([LoadPlan(id=i, job_id=1, approved=False, packed_items_count=1) for i in (1, 2)])
        db.flush()
        db.add_all([PackagePlacement(id=i, load_plan_id=i, package_id=i, step_sequence=1, pos_x=0, pos_y=0, pos_z=0, packed_length=10, packed_width=10, packed_height=10) for i in (1, 2)])
        db.commit()
    return engine


def approve(engine, plan_id=1, hold=None, release=None, pid=None):
    with Session(engine, autoflush=False) as db:
        if pid is not None:
            pid.append(db.execute(text('select pg_backend_pid()')).scalar_one())
        if hold is not None:
            def before_commit(session):
                hold.set()
                assert release.wait(10), 'Timed out waiting for test release'
            event.listen(db, 'before_commit', before_commit, once=True)
        try:
            LoadPlanService().approve_plan(plan_id, 10, db, expected_company_id=1)
            return 'OK'
        except AppException as exc:
            db.rollback()
            return exc.error_code.code


def wait_lock(engine, pid):
    deadline = time.monotonic() + 8
    while time.monotonic() < deadline:
        if pid:
            with engine.connect() as c:
                if c.execute(text('select wait_event_type from pg_stat_activity where pid=:pid'), {'pid': pid[0]}).scalar() == 'Lock':
                    return
        time.sleep(.02)
    raise AssertionError('Second transaction did not actually wait on a PostgreSQL lock')


@pytest.mark.parametrize('second_plan,expected', [(1, 'PLAN_ALREADY_APPROVED'), (2, 'OK')])
def test_concurrent_approvals_serialize_and_keep_one_approved(pg, second_plan, expected):
    held, release = Event(), Event()
    pid = []
    with ThreadPoolExecutor(max_workers=2) as pool:
        first = pool.submit(approve, pg, 1, held, release)
        assert held.wait(10)
        second = pool.submit(approve, pg, second_plan, None, None, pid)
        try:
            wait_lock(pg, pid)
        finally:
            release.set()
        assert first.result(10) == 'OK'
        assert second.result(10) == expected
    with Session(pg) as db:
        assert db.query(LoadPlan).filter_by(approved=True).count() == 1
        assert db.query(AuditLog).count() == (1 if second_plan == 1 else 2)


@pytest.mark.parametrize('mutation,expected', [
    ('stale', 'PLAN_STALE'), ('DRAFT', 'PLAN_TRIP_NOT_PLANNED'),
    ('LOADING', 'PLAN_TRIP_NOT_PLANNED'), ('IN_TRANSIT', 'PLAN_TRIP_NOT_PLANNED'),
    ('DELIVERED', 'PLAN_TRIP_NOT_PLANNED'), ('CANCELLED', 'PLAN_TRIP_NOT_PLANNED'),
])
def test_approval_rechecks_after_cooperative_mutation_commits(pg, mutation, expected):
    pid = []
    with pg.connect() as writer:
        tx = writer.begin()
        writer.execute(text('select id from trips where id=1 for update'))
        if mutation != 'stale':
            writer.execute(text('update trips set status=:state where id=1'), {'state': mutation})
        else:
            writer.execute(text('update load_plans set is_stale=true,is_approved=false where job_id=1'))
        with ThreadPoolExecutor(max_workers=1) as pool:
            result = pool.submit(approve, pg, 1, None, None, pid)
            try:
                wait_lock(pg, pid)
            finally:
                tx.commit()
            assert result.result(10) == expected
    with Session(pg) as db:
        assert db.query(LoadPlan).filter_by(approved=True).count() == 0
        assert db.query(AuditLog).count() == 0


def test_audit_database_rejection_rolls_back_target_and_old_plan(pg):
    with pg.begin() as conn:
        conn.execute(text('update load_plans set is_approved=true where id=2'))
        conn.execute(text("alter table audit_logs add constraint b5_audit_fault check (action_type <> 'PLAN_APPROVED')"))
    try:
        with pytest.raises(Exception) as failure:
            approve(pg)
        assert getattr(failure.value.orig, 'pgcode', None) == '23514'
        with Session(pg) as db:
            assert db.get(LoadPlan, 1).approved is False
            assert db.get(LoadPlan, 2).approved is True
            assert db.query(AuditLog).count() == 0
    finally:
        with pg.begin() as conn:
            conn.execute(text('alter table audit_logs drop constraint b5_audit_fault'))


def test_mutation_waits_for_approval_and_invalidates_committed_plan(pg):
    held, release = Event(), Event()
    pid = []
    def invalidate():
        with pg.begin() as c:
            pid.append(c.execute(text('select pg_backend_pid()')).scalar_one())
            c.execute(text('select id from trips where id=1 for update'))
            c.execute(text('update load_plans set is_stale=true,is_approved=false where job_id=1'))
    with ThreadPoolExecutor(max_workers=2) as pool:
        first = pool.submit(approve, pg, 1, held, release)
        assert held.wait(10)
        second = pool.submit(invalidate)
        try:
            wait_lock(pg, pid)
        finally:
            release.set()
        assert first.result(10) == 'OK'
        second.result(10)
    with Session(pg) as db:
        assert db.get(LoadPlan, 1).stale is True
        assert db.query(LoadPlan).filter_by(approved=True).count() == 0
        assert db.query(AuditLog).count() == 1


def java_mark_stale_sql():
    """Execute the repository's actual native query, rather than a copied SQL fixture."""
    source = (Path(__file__).resolve().parents[2] / 'LoadMasterService/src/main/java/'
              'fu/se184491/loadmaster_be/repository/optimization/LoadPlanRepository.java').read_text(encoding='utf-8')
    match = re.search(r'WITH locked_plans AS MATERIALIZED \(.*?FROM locked_plans locked WHERE p.id = locked.id', source, re.S)
    assert match is not None, 'Reviewed ordered native stale query is missing'
    return text(match.group())


@pytest.mark.parametrize('descending_heap', [False, True])
def test_native_bulk_stale_and_approval_share_plan_lock_order(pg, descending_heap):
    # A trigger/advisory gate controls the schedule only in this disposable schema.
    with pg.begin() as c:
        c.execute(text('update load_plans set is_approved=true where id=1'))
        if descending_heap:
            c.execute(text('create index b5_desc on load_plans (id desc)'))
            c.execute(text('cluster load_plans using b5_desc'))
        else:
            c.execute(text('cluster load_plans using load_plans_pkey'))
        first_id = 2 if descending_heap else 1
        c.execute(text(f"""create function b5_pause_first_plan() returns trigger language plpgsql as $$
            begin if NEW.id={first_id} then perform pg_advisory_xact_lock(417251); end if; return NEW; end $$"""))
        c.execute(text('create trigger b5_pause before update on load_plans for each row execute function b5_pause_first_plan()'))
    try:
        with pg.connect() as gate:
            gate.execute(text('select pg_advisory_lock(417251)'))
            writer_pid, approve_pid = [], []
            def writer():
                with pg.begin() as c:
                    writer_pid.append(c.execute(text('select pg_backend_pid()')).scalar_one())
                    c.execute(java_mark_stale_sql(), {'tripId': 1})
            with ThreadPoolExecutor(max_workers=2) as pool:
                w = pool.submit(writer)
                wait_lock(pg, writer_pid)
                a = pool.submit(approve, pg, 2, None, None, approve_pid)
                try:
                    wait_lock(pg, approve_pid)
                finally:
                    gate.execute(text('select pg_advisory_unlock(417251)'))
                w.result(15)
                assert a.result(15) == 'PLAN_STALE'
        with Session(pg) as db:
            assert db.query(LoadPlan).filter_by(approved=True).count() == 0
            assert db.query(AuditLog).count() == 0
    finally:
        with pg.begin() as c:
            c.execute(text('drop trigger b5_pause on load_plans'))
            c.execute(text('drop function b5_pause_first_plan()'))
            if descending_heap:
                c.execute(text('drop index b5_desc'))
