"""Packing profile contract: synthetic input, SQLite memory, mock HTTP dependencies only."""
from types import SimpleNamespace as NS
from unittest.mock import Mock, AsyncMock
import pytest
from app.service.optimize.async_optimization_runner import AsyncOptimizationRunner as Runner
from app.exception.app_exception import AppException

ALL = [0, 1, 2, 3, 4, 5]

def profile(value, upright=False):
    return NS(orientation=value, upright_only=upright)

def test_current_form_upright_csv_builds_without_unsupported_syntax_error():
    # Current PackageFormDialog sends 0,2: its code 2 means WLH, solver code 1.
    assert Runner.restrict_profile_rotations(ALL, profile("0,2", True)) == [0, 1]

@pytest.mark.parametrize('value, expected', [
    ('0', [0]), ('1', [2]), ('2', [1]), ('3', [3]), ('4', [4]), ('5', [5]),
    ('0,1,2,3,4,5', ALL), (' 2, 0,2 ', [0,1]),
    ('LWH',[0]), ('WLH',[1]), ('LHW',[2]), ('WHL',[3]), ('HLW',[4]), ('HWL',[5]),
    (' LWH, WLH ',[0,1]), ('lhw, hwl',[2,5]), ('WLH,WLH',[1]),
])
def test_explicit_supported_orientations(value, expected):
    assert Runner.restrict_profile_rotations(ALL,profile(value)) == expected

@pytest.mark.parametrize('value', [None, '', '  ', '\t\n'])
def test_null_blank_keeps_master_permissions(value):
    assert Runner.restrict_profile_rotations([2,4],profile(value)) == [2,4]
    assert Runner.restrict_profile_rotations([2,4],profile(value,True)) == []

def test_missing_profile_and_explicit_empty_master_never_grant_permissions():
    assert Runner.restrict_profile_rotations([1],None)==[1]
    assert Runner.restrict_profile_rotations([],profile('0,1,2,3,4,5'))==[]
    assert Runner.restrict_profile_rotations([0],profile('2'))==[]
    assert Runner.restrict_profile_rotations([2,4],profile('1,3,4'))==[2,4]
    assert Runner.restrict_profile_rotations(ALL,profile('1',True))==[]

@pytest.mark.parametrize('value',['[]','[0,1]','0;1','LWH|WLH','LWH WLH','0,LWH','0,,1',',0','0,','6','-1','01','1.0','ALL','UPRIGHT','XYZ','0'*101,True,0,[],{}])
def test_malformed_or_unsupported_profile_is_typed_validation(value):
    with pytest.raises(AppException) as error:
        Runner.restrict_profile_rotations(ALL,profile(value))
    assert error.value.error_code.code=='PACKING_PROFILE_ORIENTATION_INVALID'
    assert error.value.http_status==400
    assert 'cannot safely infer' not in str(error.value)
    # Response/log message must not echo arbitrary raw input.
    if value=='XYZ': assert 'XYZ' not in str(error.value)

@pytest.fixture
def synthetic_five():
    from sqlalchemy import create_engine
    from sqlalchemy.orm import Session
    from app.config.database import Base
    from app.entity.trip_model import (VehicleType,Vehicle,Trip,DeliveryStop,TransportOrder,
        PackageType,CargoPackage,DeliveryRequirement,DeliveryRequirementPackage,CargoPackagePackingProfile)
    engine=create_engine('sqlite:///:memory:')
    Base.metadata.create_all(engine)
    with Session(engine) as db:
        db.add_all([
            VehicleType(id=1,inner_length=620,inner_width=240,inner_height=250,door_width=210,door_height=210,max_payload_kg=5000,max_cog_offset_ratio=0.15,
                front_axle_x=100,rear_axle_x=350,front_axle_limit_kg=2200,rear_axle_limit_kg=3300,
                empty_front_axle_load_kg=1500,empty_rear_axle_load_kg=1800),
            Vehicle(id=1,vehicle_type_id=1,license_plate='SYNTHETIC'),Trip(id=12,vehicle_id=1),
            DeliveryRequirement(id=1,trip_id=12),DeliveryStop(id=1,trip_id=12,delivery_requirement_id=1,stop_sequence=1),
            TransportOrder(id=1,delivery_stop_id=1),PackageType(id=1,length=40,width=30,height=25,
                rotation_allowed=True,allow_rotate_x=True,allow_rotate_y=True,allow_rotate_z=True),
        ])
        for i,(value,upright) in enumerate([('0,2',True),('1',False),('WLH',False),(None,False),('LWH,HWL',False)],1):
            db.add(CargoPackage(id=i,order_id=1,package_type_id=1,actual_weight_kg=10))
            db.add(DeliveryRequirementPackage(delivery_requirement_id=1,package_id=i))
            db.add(CargoPackagePackingProfile(id=i,package_id=i,orientation=value,upright_only=upright))
        db.commit()
        runner=Runner.__new__(Runner)
        runner.job_service=NS(get_job=lambda *args:NS(trip_id=12,objective='MAX_VOLUME',time_limit_sec=60,algorithm_name='EP_DBLF_GA_AI'))
        yield runner,db
    engine.dispose()

def test_synthetic_five_builds_and_solver_preserves_constraints(synthetic_five):
    from app.service.optimize.optimization_client import OptimizationClient
    from tests.test_load_balance_search import assert_safe
    runner,db=synthetic_five
    request=runner.build_problem_request('synthetic-only',db)
    assert [p.allowed_rotations for p in request.packages]==[[0,1],[2],[1],ALL,[0,5]]
    assert request.time_limit_sec==60
    assert request.algorithm_name=='EP_DBLF_GA_AI'
    # Exercise only existing in-process EP/DBLF. No GA, checkpoints, inference or HTTP.
    result=OptimizationClient().solve_in_process(request,use_lumo_scorer=False)
    assert len(result.placements)==5
    ids=[str(p.package_id) for p in result.placements]+[str(p.package_id) for p in result.unplaced]
    assert sorted(ids)==['1','2','3','4','5']
    assert len(set(ids))==5
    assert_safe(result,request)

def test_invalid_profile_rejected_before_ack_or_background_task():
    from fastapi import FastAPI
    from fastapi.testclient import TestClient
    from app.controller.optimization.optimization_router import router,get_async_runner
    from app.config.database import get_db
    from app.service.auth.auth_dependency import require_optimization_service
    from app.exception.global_exception_handler import register_exception_handlers
    app=FastAPI();app.include_router(router);register_exception_handlers(app)
    runner=Mock(run=AsyncMock())
    def validate(*args):Runner.restrict_profile_rotations(ALL,profile('MALFORMED_SECRET_SENTINEL'))
    runner.validate_trip_profile_orientations.side_effect=validate
    app.dependency_overrides[get_db]=lambda: Mock()
    app.dependency_overrides[get_async_runner]=lambda: runner
    app.dependency_overrides[require_optimization_service]=lambda: {'synthetic':True}
    from unittest.mock import patch
    with patch('app.controller.optimization.optimization_router.require_authorized_job',return_value=NS(job_uuid='synthetic-job',status='PENDING')):
        response=TestClient(app).post('/api/v1/optimization/jobs',json={'trip_id':12,'job_uuid':'synthetic-job'})
    assert response.status_code==400
    assert response.json()['code']=='PACKING_PROFILE_ORIENTATION_INVALID'
    assert 'MALFORMED_SECRET_SENTINEL' not in response.text
    runner.run.assert_not_called()

def test_preflight_reads_same_trip_profiles_only(synthetic_five):
    from app.entity.trip_model import (CargoPackagePackingProfile,Trip,DeliveryRequirement,DeliveryStop,
        TransportOrder,CargoPackage,DeliveryRequirementPackage)
    runner,db=synthetic_five
    runner.validate_trip_profile_orientations(12,db)
    # A malformed profile assigned to another trip must not block this trip.
    db.add_all([Trip(id=13,vehicle_id=1),DeliveryRequirement(id=2,trip_id=13),
        DeliveryStop(id=2,trip_id=13,delivery_requirement_id=2,stop_sequence=1),
        TransportOrder(id=2,delivery_stop_id=2),CargoPackage(id=99,order_id=2,package_type_id=1,actual_weight_kg=10),
        DeliveryRequirementPackage(delivery_requirement_id=2,package_id=99),
        CargoPackagePackingProfile(id=99,package_id=99,orientation='UNKNOWN')])
    db.commit()
    runner.validate_trip_profile_orientations(12,db)
    first=db.query(CargoPackagePackingProfile).filter(CargoPackagePackingProfile.package_id==1).one()
    first.orientation='UNKNOWN'
    db.commit()
    with pytest.raises(AppException):runner.validate_trip_profile_orientations(12,db)
