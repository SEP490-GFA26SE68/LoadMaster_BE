"""Offline audit: no real DB, network, weights, inference, credit, or worker calls."""
import sys, socket, unittest, logging
from pathlib import Path
from types import SimpleNamespace as NS, ModuleType
from unittest.mock import Mock, AsyncMock, patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
network_attempts = []
_original_connect = socket.socket.connect
def blocked(*args, **kwargs):
    network_attempts.append(True)
    raise AssertionError("AUDIT_REAL_NETWORK_FORBIDDEN")
def guarded_connect(sock, address):
    caller = sys._getframe(1)
    # Windows asyncio builds a socketpair on loopback internally; no service request.
    if caller.f_code.co_name == "socketpair" and caller.f_globals.get("__name__") == "socket":
        return _original_connect(sock, address)
    return blocked()
socket.socket.connect = guarded_connect
socket.socket.connect_ex = blocked
socket.create_connection = blocked
socket.getaddrinfo = blocked

# Synthetic Settings module: never read .env or inherit real DB/identity configuration.
import ast
config_defaults = {}
settings_ast = ast.parse((ROOT / 'app/config/settings.py').read_text(encoding='utf-8-sig'))
for node in settings_ast.body:
    if isinstance(node, ast.ClassDef) and node.name == 'Settings':
        for field in node.body:
            if isinstance(field, ast.AnnAssign) and isinstance(field.target, ast.Name) and field.value is not None:
                try: config_defaults[field.target.id] = ast.literal_eval(field.value)
                except (ValueError, TypeError): pass
config_defaults.update(DATABASE_URL='sqlite:///audit_never_connect.db', KEYCLOAK_CLIENT_SECRET='SYNTHETIC_NOT_A_SECRET')
config_defaults.update(api_docs_enabled=lambda: True, cors_origins=lambda: [])
fake_settings_module = ModuleType('app.config.settings')
fake_settings_module.settings = NS(**config_defaults)
sys.modules['app.config.settings'] = fake_settings_module
from app.ml import lumo_models as lm
# Import main without loading real torch, model files, or weights.
with patch.object(lm, "torch_available", return_value=False):
    from app.main import app
from app.config.database import get_db, engine
from app.controller.optimization.optimization_router import get_async_runner, get_optimization_job_service
from app.service.auth.auth_dependency import require_optimization_service
from starlette.testclient import TestClient

# Defense in depth: any accidental use of the configured real engine fails before checkout.
engine.connect = blocked
engine.raw_connection = blocked
logging.disable(logging.NOTSET)
logging.getLogger().addHandler(logging.NullHandler())

class EndpointAudit(unittest.TestCase):
    def setUp(self):
        self.old = dict(app.dependency_overrides)
        self.db = Mock()
        self.runner = Mock(run=AsyncMock())
        self.closed = Mock()
        def db_override():
            try: yield self.db
            finally: self.closed()
        app.dependency_overrides[get_db] = db_override
        app.dependency_overrides[require_optimization_service] = lambda: {"azp": "synthetic-test-only"}
        app.dependency_overrides[get_async_runner] = lambda: self.runner
        app.dependency_overrides[get_optimization_job_service] = lambda: Mock()
        self.client = TestClient(app, raise_server_exceptions=False)
    def tearDown(self):
        self.client.close()
        app.dependency_overrides.clear()
        app.dependency_overrides.update(self.old)
    def test_health_success(self):
        r=self.client.get('/health')
        self.assertEqual(r.status_code,200)
        self.assertEqual(r.json()['status'],'UP')
        self.db.execute.assert_called_once()
        self.closed.assert_called_once()
    def test_health_execute_exception_503(self):
        self.db.execute.side_effect=RuntimeError('synthetic checkout failure')
        r=self.client.get('/health')
        self.assertEqual(r.status_code,503)
        self.assertEqual(r.json(),{'status':'DOWN','service':__import__('app.config.settings',fromlist=['settings']).settings.APP_NAME})
        self.closed.assert_called_once()
    def test_health_dependency_exception_is_500_not_503(self):
        def broken_dependency(): raise RuntimeError('synthetic session construction failure')
        app.dependency_overrides[get_db]=broken_dependency
        r=self.client.get('/health')
        self.assertEqual(r.status_code,500)
        self.assertEqual(r.json()['code'],'INTERNAL_SERVER_ERROR')
        self.db.execute.assert_not_called()
    def test_dispatch_validation_no_job_no_solver(self):
        with patch('app.controller.optimization.optimization_router.require_authorized_job') as auth:
            for body in ({'trip_id':12},{'trip_id':12,'job_uuid':''},{'job_uuid':'synthetic-job'}):
                self.assertEqual(self.client.post('/api/v1/optimization/jobs',json=body).status_code,422)
            auth.assert_not_called()
            self.runner.run.assert_not_called()
    def test_dispatch_pre_solver_exception_returns_500(self):
        with patch('app.controller.optimization.optimization_router.require_authorized_job',side_effect=RuntimeError('synthetic authorization database failure')):
            r=self.client.post('/api/v1/optimization/jobs',json={'trip_id':12,'job_uuid':'synthetic-job'})
        self.assertEqual(r.status_code,500)
        self.assertFalse(r.json()['success'])
        self.assertEqual(r.json()['code'],'INTERNAL_SERVER_ERROR')
        self.runner.run.assert_not_called()
    def test_dispatch_ack_same_uuid(self):
        with patch('app.controller.optimization.optimization_router.require_authorized_job',return_value=NS(job_uuid='synthetic-job',status='PENDING')):
            r=self.client.post('/api/v1/optimization/jobs',json={'trip_id':12,'job_uuid':'synthetic-job'})
        self.assertEqual(r.status_code,202)
        self.assertEqual(r.json()['data']['job_id'],'synthetic-job')
        self.runner.run.assert_awaited_once_with('synthetic-job')
    def test_post_ack_background_exception_does_not_replace_202(self):
        self.runner.run.side_effect=RuntimeError('synthetic worker failure after ACK')
        with patch('app.controller.optimization.optimization_router.require_authorized_job',return_value=NS(job_uuid='synthetic-job',status='PENDING')):
            r=self.client.post('/api/v1/optimization/jobs',json={'trip_id':12,'job_uuid':'synthetic-job'})
        self.assertEqual(r.status_code,202)
        self.assertEqual(r.json()['data']['job_id'],'synthetic-job')

class MockAiAudit(unittest.TestCase):
    def setUp(self):
        self.code='LUMO_R1'
        self.problem=NS(pinned=[],packages=[NS(id='synthetic-package')],model_dump=Mock(return_value={}))
        fake_torch=ModuleType('torch')
        fake_torch.Generator=lambda **kwargs: NS(manual_seed=lambda seed: object())
        fake_problem=ModuleType('app.ml.lmai.problem')
        fake_problem.Problem=NS(from_request=Mock(return_value=object()))
        self.mods=patch.dict(sys.modules,{'torch':fake_torch,'app.ml.lmai.problem':fake_problem})
        self.mods.start()
    def tearDown(self): self.mods.stop()
    def test_ai_dependencies_unavailable_skips_without_loading(self):
        with patch.object(lm,'torch_available',return_value=False),patch.object(lm,'_load') as load:
            r=lm.run_lumo_models(self.problem,[self.code],Mock(),samples=0)
        self.assertEqual(r.skipped[self.code],'TORCH_MISSING')
        load.assert_not_called()
    def test_ai_model_unavailable_skips_without_inference(self):
        with patch.object(lm,'torch_available',return_value=True),patch.object(lm,'_load',side_effect=FileNotFoundError('synthetic checkpoint missing')),patch.object(lm,'_rollout') as rollout:
            r=lm.run_lumo_models(self.problem,[self.code],Mock(),samples=0)
        self.assertEqual(r.skipped[self.code],'LOAD_FAILED')
        rollout.assert_not_called()
    def test_ai_inference_failure_skips(self):
        with patch.object(lm,'torch_available',return_value=True),patch.object(lm,'_load',return_value=(object(),object())),patch.object(lm,'_rollout',side_effect=RuntimeError('synthetic inference failure')):
            r=lm.run_lumo_models(self.problem,[self.code],Mock(),samples=0)
        self.assertEqual(r.skipped[self.code],'INFERENCE_FAILED')
        self.assertEqual(r.layouts,[])
    def test_ai_mocked_inference_success_still_validates(self):
        result=NS(verdict=NS(valid=True),placements=[])
        layout=NS(placements=[],metrics=NS(volume_utilization=0.0))
        client=Mock()
        client.evaluate_fixed_layout.return_value=(layout,None)
        with patch.object(lm,'torch_available',return_value=True),patch.object(lm,'_load',return_value=(object(),object())),patch.object(lm,'_rollout',return_value=result) as rollout:
            r=lm.run_lumo_models(self.problem,[self.code],client,samples=0)
        self.assertEqual(r.layouts,[layout])
        rollout.assert_called_once()
        client.evaluate_fixed_layout.assert_called_once()
    def test_ai_mock_success_rejected_by_final_validation(self):
        client=Mock()
        client.evaluate_fixed_layout.return_value=(None,'synthetic hard constraint')
        with patch.object(lm,'torch_available',return_value=True),patch.object(lm,'_load',return_value=(object(),object())),patch.object(lm,'_rollout',return_value=NS(verdict=NS(valid=True),placements=[])):
            r=lm.run_lumo_models(self.problem,[self.code],client,samples=0)
        self.assertEqual(r.layouts,[])
        self.assertEqual(r.skipped[self.code],'CONSTRAINT_VIOLATED')

class DiagnosticLogAudit(EndpointAudit):
    def setUp(self):
        super().setUp()
        self.messages=[]
        class Capture(logging.Handler):
            def emit(handler, record):
                self.messages.append(record.getMessage())
        self.handler=Capture()
        self.log=logging.getLogger('uvicorn.error')
        self.level=self.log.level
        self.propagate=self.log.propagate
        self.log.propagate=False
        logging.getLogger().addHandler(self.handler)
        self.log.setLevel(logging.INFO)
        self.log.addHandler(self.handler)
    def tearDown(self):
        self.log.removeHandler(self.handler)
        logging.getLogger().removeHandler(self.handler)
        self.log.propagate=self.propagate
        self.log.setLevel(self.level)
        super().tearDown()
    def records(self):
        import json
        return [json.loads(m) for m in self.messages if m.startswith('{')]
    def test_valid_correlation_and_ordered_stages(self):
        job='90759730-d2d6-3220-ae53-39711097e703'
        rid='b4e3ae45-1829-48b3-8f5c-7f8bf8fdc53f'
        with patch('app.controller.optimization.optimization_router.require_authorized_job',return_value=NS(job_uuid=job,status='PENDING')):
            response=self.client.post('/api/v1/optimization/jobs',json={'trip_id':12,'job_uuid':job},headers={'X-Request-ID':rid})
        self.assertEqual(response.status_code,202)
        records=self.records()
        self.assertEqual({r['request_id'] for r in records},{rid})
        events=[r['event'] for r in records]
        for e in ['http_request_received','authorization_started','authorization_completed','background_task_scheduled','ack_202_prepared','ack_202_started']:
            self.assertIn(e,events)
        self.assertIn(job,{r.get('job_uuid') for r in records})
    def test_dependency_before_uuid_has_request_id_and_stage(self):
        def broken():
            from app.config.diagnostics import mark
            mark('db_dependency_initialization')
            raise RuntimeError('PASSWORD_SECRET_SENTINEL')
        app.dependency_overrides[get_db]=broken
        response=self.client.post('/api/v1/optimization/jobs',json={'trip_id':12,'job_uuid':'synthetic-job'})
        self.assertEqual(response.status_code,500)
        errors=[r for r in self.records() if 'exception_type' in r]
        self.assertEqual(errors[0]['stage'],'db_dependency_initialization')
        self.assertIsNone(errors[0]['job_uuid'])
        self.assertTrue(errors[0]['request_id'])
        self.assertNotIn('PASSWORD_SECRET_SENTINEL',''.join(self.messages)+response.text)
    def test_exception_sql_parameters_tokens_payload_not_logged(self):
        from sqlalchemy.exc import OperationalError
        secret='SECRET_TOKEN_PASSWORD_CUSTOMER_SENTINEL'
        exc=OperationalError('SELECT '+secret,{'customer':secret},RuntimeError(secret))
        self.db.execute.side_effect=exc
        response=self.client.get('/health',headers={'Authorization':'Bearer '+secret,'X-Request-ID':secret})
        self.assertEqual(response.status_code,503)
        rendered=''.join(self.messages)+response.text
        self.assertNotIn(secret,rendered)
        self.assertNotIn('SELECT',rendered)
        errors=[r for r in self.records() if 'exception_type' in r]
        self.assertEqual(errors[0]['exception_type'],'OperationalError')
        self.assertEqual(errors[0]['reason_category'],'database_operational')
        self.assertTrue(errors[0]['traceback'])
        for frame in errors[0]['traceback']:
            self.assertEqual(set(frame),{'file','line','function'})
    def test_post_ack_exception_is_distinct_and_not_rethrown(self):
        self.runner.run.side_effect=RuntimeError('PRIVATE_CALLBACK_SECRET')
        with patch('app.controller.optimization.optimization_router.require_authorized_job',return_value=NS(job_uuid='synthetic-job',status='PENDING')):
            response=self.client.post('/api/v1/optimization/jobs',json={'trip_id':12,'job_uuid':'synthetic-job'})
        self.assertEqual(response.status_code,202)
        records=[r for r in self.records() if r['event']=='post_ack_exception']
        self.assertEqual(len(records),1)
        self.assertEqual(records[0]['phase'],'post_ack')
        self.assertNotIn('PRIVATE_CALLBACK_SECRET',''.join(self.messages))
    def test_auth_failure_still_denied(self):
        from app.service.auth.auth_dependency import require_optimization_service
        from app.exception.app_exception import AppException
        from app.exception.error_code import ErrorCode
        def denied():
            from app.config.diagnostics import mark
            mark('authentication_started')
            raise AppException(ErrorCode.UNAUTHORIZED)
        app.dependency_overrides[require_optimization_service]=denied
        response=self.client.post('/api/v1/optimization/jobs',json={'trip_id':12,'job_uuid':'synthetic-job'})
        self.assertEqual(response.status_code,403)
        self.runner.run.assert_not_called()
        self.assertTrue(any(r.get('stage')=='authentication_started' and 'exception_type' in r for r in self.records()))
    def test_session_initialization_vs_execute_and_cleanup(self):
        from app.config import database
        from app.config.diagnostics import _context
        import time
        ctx={'request_id':'test-only','stage':'request_received','started':time.monotonic()}
        token=_context.set(ctx)
        try:
            with patch.object(database,'SessionLocal',side_effect=RuntimeError('SECRET_INIT')):
                with self.assertRaises(RuntimeError):next(database.get_db())
            self.assertTrue(any(r['event']=='db_dependency_initialization_failed' for r in self.records()))
            session=Mock()
            with patch.object(database,'SessionLocal',return_value=session):
                gen=database.get_db()
                self.assertIs(next(gen),session)
                gen.close()
            session.close.assert_called_once()
        finally:_context.reset(token)
    def test_startup_identity_allowlist_only(self):
        import os
        from app.config.diagnostics import startup_identity
        with patch.dict(os.environ,{'LOADMASTER_BUILD_REVISION':'rev-safe-123','SECRET_TEST_VALUE':'NEVER_LOG_THIS'}):
            startup_identity(__file__)
        r=[x for x in self.records() if x['event']=='worker_startup'][0]
        self.assertEqual(set(r),{'event','pid','python_executable','cwd','app_module','revision'})
        self.assertEqual(r['revision'],'rev-safe-123')
        self.assertNotIn('NEVER_LOG_THIS',''.join(self.messages))
    def test_auth_decode_failure_is_safe_without_real_jwks(self):
        from app.service.auth import auth_dependency as auth
        from app.config.diagnostics import _context
        from app.exception.app_exception import AppException
        import time
        token=_context.set({'request_id':'test-only','stage':'authentication_started','started':time.monotonic()})
        try:
            with patch.object(auth.jwks_client,'get_signing_key_from_jwt',side_effect=ValueError('SECRET_TOKEN_VALUE')):
                with self.assertRaises(AppException):auth.decode_token('SECRET_TOKEN_VALUE')
        finally:_context.reset(token)
        self.assertNotIn('SECRET_TOKEN_VALUE',''.join(self.messages))
        self.assertTrue(any(r['event']=='authentication_failed' for r in self.records()))

    def test_real_auth_guards_and_job_located_with_fake_jwt_and_session(self):
        from app.service.auth import auth_dependency as auth
        app.dependency_overrides.pop(require_optimization_service,None)
        job_id='90759730-d2d6-3220-ae53-39711097e703'
        claims={'azp':'loadmaster-backend-service','resource_access':{'optimization-service':{'roles':['OPTIMIZATION_EXECUTE']}}}
        job=NS(job_uuid=job_id,status='PENDING',trip_id=12,company_id='1',parent_plan_id=None,subscription_tier='BASIC',algorithm_tier='BASIC')
        job_query=Mock();job_query.filter.return_value=job_query;job_query.first.return_value=job
        trip_query=Mock();trip_query.filter.return_value=trip_query;trip_query.first.return_value=NS(company_id=1)
        self.db.query.side_effect=[job_query,trip_query]
        self.db.execute.return_value.first.return_value=(1,)
        with patch.object(auth,'decode_token',return_value=claims):
            response=self.client.post('/api/v1/optimization/jobs',json={'trip_id':12,'job_uuid':job_id},headers={'Authorization':'Bearer SYNTHETIC_ONLY'})
        self.assertEqual(response.status_code,202)
        events=[r['event'] for r in self.records()]
        for name in ['authentication_started','authentication_completed','job_lookup_started','job_located','authorization_checks_started','authorization_completed']:
            self.assertIn(name,events)
        self.assertLess(events.index('authentication_completed'),events.index('authorization_started'))
        self.assertNotIn('SYNTHETIC_ONLY',''.join(self.messages))
    def test_real_service_identity_guard_denies_fake_wrong_identity(self):
        from app.service.auth import auth_dependency as auth
        app.dependency_overrides.pop(require_optimization_service,None)
        claims={'azp':'wrong-service','resource_access':{'optimization-service':{'roles':['OPTIMIZATION_EXECUTE']}}}
        with patch.object(auth,'decode_token',return_value=claims):
            response=self.client.post('/api/v1/optimization/jobs',json={'trip_id':12,'job_uuid':'90759730-d2d6-3220-ae53-39711097e703'},headers={'Authorization':'Bearer SYNTHETIC_ONLY'})
        self.assertEqual(response.status_code,403)
        self.runner.run.assert_not_called()
        self.assertFalse(any(r['event']=='authentication_completed' for r in self.records()))
    def test_context_does_not_leak_job_into_next_request(self):
        with patch('app.controller.optimization.optimization_router.require_authorized_job',return_value=NS(job_uuid='90759730-d2d6-3220-ae53-39711097e703',status='PENDING')):
            self.client.post('/api/v1/optimization/jobs',json={'trip_id':12,'job_uuid':'90759730-d2d6-3220-ae53-39711097e703'})
        self.messages.clear()
        self.assertEqual(self.client.get('/health').status_code,200)
        self.assertTrue(self.records())
        self.assertTrue(all(r.get('job_uuid') is None for r in self.records()))
    def test_lifespan_emits_startup_identity_without_connection(self):
        with TestClient(app,raise_server_exceptions=False) as client:
            self.assertEqual(client.get('/health').status_code,200)
        self.assertTrue(any(r['event']=='worker_startup' for r in self.records()))

if __name__ == '__main__':
    suite=unittest.defaultTestLoader.loadTestsFromModule(sys.modules[__name__])
    result=unittest.TextTestRunner(verbosity=2).run(suite)
    print('Real network attempts:',len(network_attempts))
    sys.exit(0 if result.wasSuccessful() and not network_attempts else 1)
