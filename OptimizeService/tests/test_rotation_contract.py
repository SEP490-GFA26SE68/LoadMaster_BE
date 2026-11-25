"""Whitelist and geometry regressions; synthetic data, no dev services/database."""
import time
from types import SimpleNamespace
from unittest.mock import Mock

import pytest
from pydantic import ValidationError

from app.dto.optimization.engine.problem_request import PackageData
from app.dto.optimization.engine.engine_response import PlacementData, EngineOptimizationResponse
from app.service.optimize.constraint_engine import ConstraintEngine
from app.service.optimize.load_balance_search import LoadBalanceSearch, validate_plan
from app.service.optimize.optimization_client import OptimizationClient
from tests.test_load_balance_search import package, problem, placement, assert_safe


def payload(**kw):
    return dict(id='A', l=40, w=30, h=25, weight=10, **kw)


def test_missing_is_original_orientation_only():
    p = PackageData(**payload())
    assert p.allowed_rotations == [0]
    assert not ConstraintEngine.check_rotation_allowed(p, placement(p, 290).model_copy(update={'rotation_type': 1})).passed


@pytest.mark.parametrize('value', [None, [-1], [6], ['LWH'], [1.5], [True], ['01'], ['1.0'], '0'])
def test_invalid_whitelist_is_rejected(value):
    with pytest.raises(ValidationError):
        PackageData(**payload(allowed_rotations=value))


@pytest.mark.parametrize('locked', [False, True])
def test_empty_list_permits_none_in_initial_search_and_final(locked):
    p = package('A', allowed_rotations=[], rotation_allowed=not locked)
    req = problem([p])
    original = placement(p, 290)
    assert not ConstraintEngine.check_rotation_allowed(p, original).passed
    assert validate_plan([original], req.vehicle, {'A': p}, time.monotonic()+2) == ['ORIENTATION_NOT_ALLOWED']
    assert LoadBalanceSearch(req, time.monotonic()+2).improve([original], {}) == []
    result = OptimizationClient().solve_in_process(req)
    assert result.placements == []
    assert [str(u.package_id) for u in result.unplaced] == ['A']
    assert 'ORIENTATION_NOT_ALLOWED' in result.unplaced[0].violated_constraints


@pytest.mark.parametrize('codes', [[1], [1, 3], ['1', '3']])
def test_explicit_whitelist_is_preserved_by_solver_and_improvement(codes):
    p = package('A', allowed_rotations=codes, rotation_allowed=True)
    req = problem([p])
    result = OptimizationClient().solve_in_process(req)
    assert len(result.placements) == 1
    assert int(result.placements[0].rotation_type) in {int(x) for x in codes}
    assert_safe(result, req)
    improved = LoadBalanceSearch(req, time.monotonic()+2).improve(result.placements, {})
    assert len(improved) == 1
    assert int(improved[0].rotation_type) in {int(x) for x in codes}


def test_locked_switch_intersects_whitelist_instead_of_granting_zero():
    p = package('A', allowed_rotations=[1], rotation_allowed=False)
    result = OptimizationClient().solve_in_process(problem([p]))
    assert not result.placements
    assert [u.package_id for u in result.unplaced] == ['A']


def test_forbidden_but_fitting_rotation_is_rejected_by_final_and_partial():
    p = package('A', allowed_rotations=[0], rotation_allowed=True)
    req = problem([p])
    wrong = placement(p, 290).model_copy(update=dict(rotation_type=1, packed_l=30, packed_w=40))
    assert validate_plan([wrong], req.vehicle, {'A':p}, time.monotonic()+2) == ['ORIENTATION_NOT_ALLOWED']
    best = LoadBalanceSearch(req, time.monotonic()+2).improve([wrong], {})
    assert validate_plan(best, req.vehicle, {'A':p}, time.monotonic()+2) == []
    assert all(p.rotation_type == 0 for p in best)


def test_allowed_code_with_wrong_packed_dimensions_is_rejected():
    p = package('A', allowed_rotations=[1], rotation_allowed=True)
    assert not ConstraintEngine.check_rotation_allowed(p, placement(p, 290).model_copy(update={'rotation_type': 1})).passed


@pytest.mark.parametrize('code', [None, 6, -1, True, 1.5, 'bad'])
def test_invalid_placement_rotation_rejected_at_contract(code):
    with pytest.raises(ValidationError):
        PlacementData(package_id='A', x=0,y=0,z=0,packed_l=40,packed_w=30,packed_h=25,rotation_type=code)


@pytest.mark.parametrize('code,dims', [(0,(40,30,25)),(1,(30,40,25)),(2,(40,25,30)),(3,(30,25,40)),(4,(25,40,30)),(5,(25,30,40))])
def test_rotation_geometry_survives_persistence(code, dims):
    from app.dto.optimization.engine.rotation_contract import validate_result_rotations
    from app.service.optimize.optimization_result_persistence_service import OptimizationResultPersistenceService
    p = package('1', allowed_rotations=[code], rotation_allowed=True)
    packed = PlacementData(package_id='1',x=100,y=60,z=0,packed_l=dims[0],packed_w=dims[1],packed_h=dims[2],rotation_type=str(code))
    result = EngineOptimizationResponse(placements=[packed])
    validate_result_rotations(problem([p]),result)
    plan=SimpleNamespace(id=123,approved=False,packed_items_count=0)
    plans=Mock();plans.find_by_job_id.return_value=[plan]
    placements=Mock(); placements.find_by_plan_id.return_value=[]
    unplaced=Mock(); unplaced.find_by_plan_id.return_value=[]
    jobs=Mock();jobs.get_job.return_value=SimpleNamespace(id=9,job_uuid='test',execution_generation=None)
    db=Mock()
    svc=OptimizationResultPersistenceService(jobs,plans,placements,unplaced)
    svc.save('test',result,db)
    row=placements.create_all.call_args.args[0][0]
    assert (row.rotation_type,row.packed_length,row.packed_width,row.packed_height) == (code,*dims)


def test_pre_persistence_boundary_rejects_forbidden_orientation():
    from app.dto.optimization.engine.rotation_contract import validate_result_rotations
    p=package('A',allowed_rotations=[0])
    result=EngineOptimizationResponse(placements=[placement(p,290).model_copy(update=dict(rotation_type=1,packed_l=30,packed_w=40))])
    with pytest.raises(ValueError,match='forbidden rotation'):
        validate_result_rotations(problem([p]),result)


def test_profile_without_restriction_does_not_expand_master_list():
    from app.service.optimize.async_optimization_runner import AsyncOptimizationRunner as R
    assert R.restrict_profile_rotations([1],None)==[1]
    assert R.restrict_profile_rotations([0,1,2,3,4,5],SimpleNamespace(orientation=None,upright_only=True))==[0,1]
    assert R.restrict_profile_rotations([2],SimpleNamespace(orientation='',upright_only=True))==[]


@pytest.mark.parametrize('value',['[]','LWH|WLH','0,,1'])
def test_unsupported_profile_syntax_fails_closed(value):
    from app.service.optimize.async_optimization_runner import AsyncOptimizationRunner as R
    from app.exception.app_exception import AppException
    with pytest.raises(AppException) as error:
        R.restrict_profile_rotations([0,1,2,3,4,5],SimpleNamespace(orientation=value,upright_only=False))
    assert error.value.error_code.code == 'PACKING_PROFILE_ORIENTATION_INVALID'


@pytest.mark.asyncio
async def test_remote_engine_cannot_bypass_rotation_without_obstacles(monkeypatch):
    from unittest.mock import AsyncMock
    p=package('A',allowed_rotations=[0])
    result=EngineOptimizationResponse(placements=[placement(p,290).model_copy(update=dict(rotation_type=1,packed_l=30,packed_w=40))])
    client=OptimizationClient(engine_url='http://127.0.0.1:1')
    monkeypatch.setattr(client,'_solve_http',AsyncMock(return_value=result))
    with pytest.raises(ValueError,match='forbidden rotation'):
        await client.solve(problem([p]))


@pytest.mark.parametrize('profile', [None, (None,True), ('',False), ('LWH',False)])
def test_database_profile_reaches_problem_request(profile):
    from sqlalchemy import create_engine
    from sqlalchemy.orm import Session
    from app.config.database import Base
    from app.entity.trip_model import (VehicleType,Vehicle,Trip,DeliveryStop,TransportOrder,
        PackageType,CargoPackage,DeliveryRequirement,DeliveryRequirementPackage,CargoPackagePackingProfile)
    from app.service.optimize.async_optimization_runner import AsyncOptimizationRunner
    engine=create_engine('sqlite:///:memory:')
    try:
        Base.metadata.create_all(engine)
        with Session(engine) as db:
            db.add_all([
                VehicleType(id=1,inner_length=620,inner_width=240,inner_height=250,max_payload_kg=5000,max_cog_offset_ratio=0.15),
                Vehicle(id=1,vehicle_type_id=1,license_plate='ISOLATED'),Trip(id=1,vehicle_id=1),
                DeliveryRequirement(id=1,trip_id=1),DeliveryStop(id=1,trip_id=1,delivery_requirement_id=1,stop_sequence=1),
                TransportOrder(id=1,delivery_stop_id=1),PackageType(id=1,length=40,width=30,height=25,
                    rotation_allowed=True,allow_rotate_x=True,allow_rotate_y=True,allow_rotate_z=True),
                CargoPackage(id=1,order_id=1,package_type_id=1,actual_weight_kg=10),
                DeliveryRequirementPackage(delivery_requirement_id=1,package_id=1),
            ])
            if profile is not None:
                db.add(CargoPackagePackingProfile(id=1,package_id=1,orientation=profile[0],upright_only=profile[1]))
            db.commit()
            runner=AsyncOptimizationRunner.__new__(AsyncOptimizationRunner)
            runner.job_service=SimpleNamespace(get_job=lambda *args:SimpleNamespace(trip_id=1,objective='MAX_VOLUME',time_limit_sec=60))
            request=runner.build_problem_request('isolated',db)
            expected = [0] if profile and profile[0] == 'LWH' else ([0,1] if profile and profile[1] else [0,1,2,3,4,5])
            assert request.packages[0].allowed_rotations == expected

    finally:
        engine.dispose()


def test_empty_whitelist_keeps_other_packages_as_safe_partial():
    good=package('A')
    blocked=package('B',allowed_rotations=[],rotation_allowed=True)
    req=problem([good,blocked])
    result=OptimizationClient().solve_in_process(req)
    assert [p.package_id for p in result.placements] == ['A']
    assert [p.package_id for p in result.unplaced] == ['B']
    assert_safe(result,req)


@pytest.mark.parametrize('value', [6, -1, 9, 'abc', 'LWH', 1.5, True, '01', None])
def test_pinned_rotation_outside_whitelist_is_rejected(value):
    from app.dto.optimization.engine.problem_request import PinnedData
    with pytest.raises(ValidationError):
        PinnedData(package_id='A', pos_x=0, pos_y=0, pos_z=0, rotation_type=value)


@pytest.mark.parametrize('value, code', [(0, 0), (5, 5), ('3', 3)])
def test_pinned_rotation_accepts_codes_and_canonical_strings(value, code):
    from app.dto.optimization.engine.problem_request import PinnedData
    assert PinnedData(package_id='A', pos_x=0, pos_y=0, pos_z=0, rotation_type=value).rotation_type == code
