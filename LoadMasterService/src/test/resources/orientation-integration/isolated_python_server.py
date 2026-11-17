"""Owned test server only: PostgreSQL Testcontainer, mocked JWT verification, real EP/DBLF."""
import sys,json,socket,runpy,logging,threading,asyncio
from pathlib import Path
from unittest.mock import Mock,AsyncMock
from types import SimpleNamespace as NS
raw_connect=socket.socket.connect
raw_connect_ex=socket.socket.connect_ex
raw_create=socket.create_connection
raw_dns=socket.getaddrinfo
config=json.loads(Path(sys.argv[1]).read_text(encoding='utf-8'))
assert config['host']=='127.0.0.1' and config['pg_port']!=5432 and config['database']=='test'
root=Path(config['python_root']);sys.path.insert(0,str(root))
bootstrap=runpy.run_path(str(root/'docs/diagnostics/offline_diagnostics.py'),run_name='offline_bootstrap')
# Keep synthetic Settings and blocked default engine, allow sockets only to our test endpoints.
allowed_ports={config['pg_port']}
def allowed(address):return isinstance(address,tuple) and address[0] in ('127.0.0.1','localhost') and address[1] in allowed_ports
def connect(sock,address):
    frame=sys._getframe(1)
    if frame.f_code.co_name=='socketpair' and frame.f_globals.get('__name__')=='socket':return raw_connect(sock,address)
    if allowed(address):return raw_connect(sock,address)
    return bootstrap['blocked']()
socket.socket.connect=connect
socket.socket.connect_ex=lambda sock,address:raw_connect_ex(sock,address) if allowed(address) else bootstrap['blocked']()
socket.create_connection=lambda address,*a,**k:raw_create(address,*a,**k) if allowed(address) else bootstrap['blocked']()
socket.getaddrinfo=lambda host,port,*a,**k:raw_dns(host,port,*a,**k) if host in ('127.0.0.1','localhost') and port in allowed_ports else bootstrap['blocked']()
from sqlalchemy import create_engine,text
from sqlalchemy.engine import URL
from sqlalchemy.orm import sessionmaker
from app.config.database import Base,get_db
url=URL.create('postgresql+psycopg2',username=config['username'],password=config['password'],host=config['host'],port=config['pg_port'],database=config['database'])
engine=create_engine(url,pool_pre_ping=True,echo=False,connect_args={'connect_timeout':5})
with engine.connect() as db:
    assert str(db.execute(text('select marker from orientation_test_marker')).scalar())==config['nonce']
Base.metadata.create_all(engine)  # Fixture schema only on this verified disposable Testcontainer.
Sessions=sessionmaker(bind=engine)
from app.main import app
from app.controller.optimization.optimization_router import get_async_runner
from app.service.auth import auth_dependency as auth
from app.exception.app_exception import AppException
from app.exception.error_code import ErrorCode
from app.service.optimize.async_optimization_runner import AsyncOptimizationRunner
from app.service.optimize.optimization_client import OptimizationClient
from app.service.optimize.engine_exception_handler import EngineExceptionHandler
from app.service.optimize.optimization_job_service import OptimizationJobService
from app.config.diagnostics import canonical_uuid
from app.entity.trip_model import (VehicleType,Vehicle,Trip,DeliveryStop,TransportOrder,PackageType,CargoPackage,DeliveryRequirement,DeliveryRequirementPackage,CargoPackagePackingProfile)
from tests.test_load_balance_search import assert_safe
state={}
finished_event=threading.Event()
deadline_flushed=threading.Event()
deadline_release=threading.Event()
gates={phase:threading.Event() for phase in ("worker","solver")}
def clear_state():
    finished_event.clear()
    deadline_flushed.clear()
    deadline_release.clear()
    state.clear();state.update(run_calls=0,finished_calls=0,solve_calls=0,builds=[],http=[],events=[])
    for gate in gates.values():gate.set()
clear_state()
class Capture(logging.Handler):
    def emit(self,record):
        try:
            value=json.loads(record.getMessage())
            if isinstance(value,dict) and 'event' in value:state['events'].append(value)
        except (ValueError,TypeError):pass
logging.getLogger('uvicorn.error').addHandler(Capture())
def decode_fake(token):
    if token!='SYNTHETIC_SERVICE_TOKEN_SENTINEL':raise AppException(ErrorCode.UNAUTHENTICATED)
    return {'azp':'loadmaster-backend-service','resource_access':{'optimization-service':{'roles':['OPTIMIZATION_EXECUTE']}}}
auth.decode_token=decode_fake

def get_test_db():
    with Sessions() as db:yield db
app.dependency_overrides[get_db]=get_test_db
class RealEpClient:
    async def solve(self,problem):
        state['solve_calls']+=1
        assert await asyncio.to_thread(gates['solver'].wait,20), 'Owned test solver gate timed out'
        result=OptimizationClient().solve_in_process(problem,use_lumo_scorer=False)
        assert_safe(result,problem)
        assert len(result.placements)==5
        return result
class ObservedRunner(AsyncOptimizationRunner):
    async def run_owned(self,job_uuid,*args,**kwargs):
        state['run_calls']+=1
        try:
            assert await asyncio.to_thread(gates['worker'].wait,20), 'Owned worker gate timed out'
            return await super().run_owned(job_uuid,*args,**kwargs)
        finally:
            state['finished_calls']+=1
            finished_event.set()
    async def run(self,job_uuid,*args,**kwargs):
        state['run_calls']+=1
        try:
            assert await asyncio.to_thread(gates['worker'].wait,20), 'Owned test worker gate timed out'
            return await super().run(job_uuid,*args,**kwargs)
        finally:state['finished_calls']+=1
    def build_problem_request(self,job_uuid,db):
        problem=super().build_problem_request(job_uuid,db)
        rotations=[p.allowed_rotations for p in problem.packages]
        assert rotations==[[0,1],[2],[1],[0,1,2,3,4,5],[0,5]]
        state['builds'].append({'job_uuid':str(job_uuid),'rotations':rotations,'package_count':len(problem.packages)})
        return problem
credits=Mock(refund_credit=AsyncMock(),deduct_credit=AsyncMock())
jobs=OptimizationJobService()
runner=ObservedRunner(job_service=jobs,optimization_client=RealEpClient(),session_factory=Sessions,
    credit_client=credits,engine_exception_handler=EngineExceptionHandler(job_service=jobs,credit_client=credits,notification_service=Mock()))
app.dependency_overrides[get_async_runner]=lambda:runner
from fastapi import Header,HTTPException

def check_nonce(value):
    if value!=config['nonce']:raise HTTPException(403,'Test control denied')
@app.post('/__test__/gate')
def gate(phase:str,hold:bool=False,x_test_nonce:str=Header(default='')):
    check_nonce(x_test_nonce)
    if phase not in gates:raise HTTPException(400,'Invalid test gate')
    (gates[phase].clear if hold else gates[phase].set)()
    return {'held':hold}
@app.get('/__test__/ready')
def ready(x_test_nonce: str=Header(default='')):
    check_nonce(x_test_nonce);return {'ready':True}
@app.get('/__test__/await-finished')
async def await_finished(x_test_nonce:str=Header(default='')):
    check_nonce(x_test_nonce)
    assert await asyncio.to_thread(finished_event.wait,20), 'Worker did not finish'
    return {'finished':True}
# Fault injection AFTER the actual PostgreSQL ownership commit, before scheduling/ACK.
from app.service.optimize.job_execution_protocol import JobExecutionProtocol
original_prepare=JobExecutionProtocol.prepare
def prepare_with_fault(self,*args,**kwargs):
    prepared=original_prepare(self,*args,**kwargs)
    if prepared.problem_json is not None and state.get('fail_after_prepare'):
        raise RuntimeError('Synthetic crash after durable owner commit')
    return prepared
JobExecutionProtocol.prepare=prepare_with_fault
@app.post('/__test__/fail-after-prepare')
def fail_after_prepare(enabled:bool=True,x_test_nonce:str=Header(default='')):
    check_nonce(x_test_nonce);state['fail_after_prepare']=enabled
    return {'enabled':enabled}
@app.post('/__test__/enable-v2')
def enable_v2(x_test_nonce:str=Header(default='')):
    check_nonce(x_test_nonce)
    from app.config.settings import settings
    settings.OPTIMIZATION_LIFECYCLE_V2_ENABLED=True
    return {'enabled':True}
@app.get('/__test__/mapping-probe')
def mapping_probe(uuid:str,x_test_nonce:str=Header(default='')):
    check_nonce(x_test_nonce)
    from app.entity.optimization.optimization_job import OptimizationJob
    from sqlalchemy.exc import DBAPIError
    from app.config.settings import settings
    with Sessions() as db:
        try:
            job=db.query(OptimizationJob).filter_by(job_uuid=uuid).one()
            return {'mapped':True,'v2_flag':settings.OPTIMIZATION_LIFECYCLE_V2_ENABLED,
                    'generation':job.execution_generation,
                    'dispatch_expires_at':job.dispatch_expires_at.isoformat() if job.dispatch_expires_at else None}
        except DBAPIError as exc:
            db.rollback()
            return {'mapped':False,'v2_flag':settings.OPTIMIZATION_LIFECYCLE_V2_ENABLED,
                    'sqlstate':getattr(exc.orig,'pgcode',None)}
@app.post('/__test__/owner-check')
def owner_check(uuid:str,generation:int,operation:str,x_test_nonce:str=Header(default='')):
    check_nonce(x_test_nonce)
    from app.service.optimize.job_execution_protocol import JobExecutionProtocol,OwnershipLost
    protocol=JobExecutionProtocol(Sessions)
    try:
        if operation=='begin':return {'allowed':protocol.begin(uuid,generation)}
        if operation=='heartbeat':protocol.heartbeat(uuid,generation)
        elif operation=='finalize':protocol.finalize(uuid,generation,'COMPLETED')
        elif operation=='rollback':
            from app.entity.optimization.load_plan import LoadPlan
            from app.entity.optimization.optimization_job import OptimizationJob
            def broken_write(db):
                job=db.query(OptimizationJob).filter_by(job_uuid=uuid).one()
                db.add(LoadPlan(job_id=job.id,approved=False));db.flush()
                raise RuntimeError('Synthetic failure after plan flush')
            try:protocol.finalize(uuid,generation,'COMPLETED',broken_write)
            except RuntimeError:return {'rolled_back':True}
        else:raise HTTPException(400,'Unknown fixture operation')
        return {'allowed':True}
    except OwnershipLost:return {'allowed':False}
# Real PostgreSQL transaction probe, nonce-protected and test-server-only.
@app.get('/__test__/deadline-state')
def deadline_state(x_test_nonce:str=Header(default='')):
    check_nonce(x_test_nonce)
    return {'flushed':deadline_flushed.is_set()}

@app.post('/__test__/deadline-release')
def release_deadline(x_test_nonce:str=Header(default='')):
    check_nonce(x_test_nonce);deadline_release.set()
    return {'released':True}

@app.post('/__test__/deadline-persist')
def deadline_persist(uuid:str,mode:str='before',x_test_nonce:str=Header(default='')):
    check_nonce(x_test_nonce)
    if mode not in ('before','absolute','lease','hold','hold-lease'):
        raise HTTPException(400,'Unknown deadline fixture mode')
    from app.dto.optimization.engine.engine_response import EngineOptimizationResponse,PlacementData,UnplacedData
    from app.entity.optimization.optimization_job import OptimizationJob
    from app.service.optimize.job_execution_protocol import OwnershipLost
    # Synthetic persistence-layer fixture: four placements and one unplaced package.
    # Real solver/final geometry checks remain covered by the existing five-package HTTP tests.
    result=EngineOptimizationResponse(placements=[
        PlacementData(package_id=str(i),x=250+(i-1)*45,y=100,z=0,
                      packed_l=l,packed_w=w,packed_h=h,rotation_type=r,step_sequence=i)
        for i,l,w,h,r in [(1,40,30,25,0),(2,40,25,30,2),(3,30,40,25,1),(4,40,30,25,0)]],
        unplaced=[UnplacedData(package_id='5',reason='NO_SPACE')])
    observed={}
    protocol=JobExecutionProtocol(Sessions,lease_seconds=120,heartbeat_seconds=5,grace_seconds=0)
    def write(db):
        runner.persistence_service.save(uuid,result,db)
        db.flush()
        for table in ('load_plans','package_placements','unplaced_packages'):
            observed[table]=db.scalar(text('SELECT count(*) FROM '+table))
        assert observed=={'load_plans':1,'package_placements':4,'unplaced_packages':1}
        deadline_flushed.set()
        if mode in ('hold','hold-lease'):
            assert deadline_release.wait(15), 'Finalize fixture was not released'
        if mode in ('absolute','lease','hold-lease'):
            expression=("execution_started_at + time_limit_sec * interval '1 second'"
                        if mode=='absolute' else 'lease_expires_at')
            # Uses the real DB clock; never changes the production clock or timing policy.
            db.execute(text("SELECT pg_sleep(greatest(0,extract(epoch from (("+expression+")-clock_timestamp())))+0.05) FROM optimization_jobs WHERE job_uuid=:uuid"),{'uuid':uuid})
    try:
        protocol.finalize(uuid,1,'PARTIAL',write_result=write)
        allowed=True
    except OwnershipLost:
        allowed=False
    return {'allowed':allowed,'flushed_counts':observed,'refund_calls':credits.refund_credit.call_count,
            'deduct_calls':credits.deduct_credit.call_count,'blocked_network_attempts':len(bootstrap['network_attempts'])}

@app.post('/__test__/seed')
def seed(mode:str='valid',x_test_nonce:str=Header(default='')):
    check_nonce(x_test_nonce);clear_state();credits.reset_mock()
    with Sessions() as db:
        db.add_all([VehicleType(id=1,inner_length=620,inner_width=240,inner_height=250,door_width=210,door_height=210,max_payload_kg=5000,max_cog_offset_ratio=0.15,
            front_axle_x=100,rear_axle_x=350,front_axle_limit_kg=2200,rear_axle_limit_kg=3300,empty_front_axle_load_kg=1500,empty_rear_axle_load_kg=1800),
            Vehicle(id=1,vehicle_type_id=1,license_plate="SYNTHETIC"),DeliveryRequirement(id=1,trip_id=7),DeliveryStop(id=1,trip_id=7,delivery_requirement_id=1,stop_sequence=1),
            TransportOrder(id=1,delivery_stop_id=1),PackageType(id=1,length=40,width=30,height=25,rotation_allowed=True,allow_rotate_x=True,allow_rotate_y=True,allow_rotate_z=True)])
        db.query(Trip).filter(Trip.id==7).update({'vehicle_id':1})
        for i,(orientation,upright) in enumerate([('0,2',True),('1',False),('WLH',False),(None,False),('LWH,HWL',False)],1):
            if mode=='invalid' and i==1:orientation='PRIVATE_PACKAGE_CUSTOMER_SENTINEL'
            db.add(CargoPackage(id=i,order_id=1,package_type_id=1,actual_weight_kg=10))
            db.add(DeliveryRequirementPackage(delivery_requirement_id=1,package_id=i))
            db.add(CargoPackagePackingProfile(id=i,package_id=i,orientation=orientation,upright_only=upright))
        db.commit()
    return {'seeded':True}
def has_completed():
    from app.entity.optimization.optimization_job import OptimizationJob
    with Sessions() as db:return db.query(OptimizationJob).filter_by(status='COMPLETED').first() is not None
@app.get('/__test__/state')
def snapshot(x_test_nonce:str=Header(default='')):
    check_nonce(x_test_nonce)
    if state['builds'] and state['finished_calls']==state['run_calls'] and has_completed():
        from app.entity.optimization.package_placement import PackagePlacement
        from app.dto.optimization.engine.engine_response import PlacementData
        from app.dto.optimization.engine.rotation_contract import validate_result_rotations
        with Sessions() as db:
            problem=AsyncOptimizationRunner.build_problem_request(runner,state['builds'][0]['job_uuid'],db)
            rows=db.query(PackagePlacement).order_by(PackagePlacement.step_sequence).all()
            placements=[PlacementData(package_id=str(p.package_id),x=float(p.pos_x),y=float(p.pos_y),z=float(p.pos_z),packed_l=float(p.packed_length),packed_w=float(p.packed_width),packed_h=float(p.packed_height),rotation_type=p.rotation_type,step_sequence=p.step_sequence) for p in rows]
            candidate,error=OptimizationClient().evaluate_fixed_layout(problem,placements,'EP_DBLF')
            assert error is None and candidate is not None and len(placements)==5
            validate_result_rotations(problem,candidate);assert_safe(candidate,problem)
            state['persisted_verified']=True
    return {**state,'refund_calls':credits.refund_credit.call_count,'deduct_calls':credits.deduct_credit.call_count,'blocked_network_attempts':len(bootstrap['network_attempts'])}
class ResponseObserver:
    def __init__(self,inner):self.inner=inner
    async def __call__(self,scope,receive,send):
        record=None
        if scope.get('type')=='http' and scope.get('path') in ('/api/v1/optimization/jobs','/api/v2/optimization/jobs'):
            request_id=next((v.decode('ascii') for k,v in scope['headers'] if k==b'x-request-id'),'')
            record={'request_id':canonical_uuid(request_id),'status':None,'code':None,'job_uuid':None}
            state['http'].append(record)
        async def observe(message):
            if record is not None:
                if message['type']=='http.response.start':record['status']=message['status']
                if message['type']=='http.response.body' and message.get('body'):
                    data=json.loads(message['body']);record['code']=data.get('code');record['job_uuid']=data.get('data',{}).get('job_id') if data.get('data') else None
            await send(message)
        await self.inner(scope,receive,observe)
if __name__=='__main__':
    import uvicorn
    bound=socket.socket(socket.AF_INET,socket.SOCK_STREAM);bound.bind(('127.0.0.1',0))
    port=bound.getsockname()[1];allowed_ports.add(port)
    Path(config['ready_file']).write_text(json.dumps({'port':port}),encoding='utf-8')
    server=uvicorn.Server(uvicorn.Config(ResponseObserver(app),host='127.0.0.1',port=port,log_level='info',access_log=False))
    logging.getLogger('uvicorn.error').addHandler(Capture())
    try:server.run(sockets=[bound])
    finally:engine.dispose()
