"""Synthetic remote-result contracts; real validators, no packing solver."""
from copy import deepcopy
import pytest
from app.dto.optimization.engine.problem_request import ProblemRequest, VehicleData, PackageData
from app.dto.optimization.engine.engine_response import (
    EngineOptimizationResponse as Result, CandidatePlanData as Candidate,
    PlacementData, UnplacedData,
)
from app.service.optimize.optimization_client import OptimizationClient
from app.service.optimize.engine_exception_handler import EngineExceptionHandler
from unittest.mock import Mock


def problem():
    return ProblemRequest(vehicle=VehicleData(
        inner_l=600, inner_w=240, inner_h=250, max_payload_kg=10000,
        door_w=240, door_h=250, front_axle_x=0, rear_axle_x=400,
        front_axle_limit_kg=5000, rear_axle_limit_kg=6000,
        empty_front_axle_load_kg=2000, empty_rear_axle_load_kg=2000,
        max_cog_offset_ratio=.5), packages=[
            PackageData(id=str(i), l=100, w=80, h=60, weight=100)
            for i in (1, 2)])


def placement(package_id='1', x=150):
    return PlacementData(package_id=package_id, x=x, y=80, z=0,
                         packed_l=100, packed_w=80, packed_h=60)


def layout(kind):
    if kind == 'complete':
        return Result(placements=[placement(), placement('2', 250)])
    return Result(placements=[placement()] if kind == 'partial' else [],
                  unplaced=[UnplacedData(package_id=str(i), reason='OUT_OF_SPACE')
                            for i in ((2,) if kind == 'partial' else (1, 2))])


def with_candidates(candidate_kind='partial'):
    result = layout('partial')
    result.candidates = [Candidate(label=label, objective='MAX_VOLUME',
                                   result=layout(candidate_kind)) for label in 'ABC']
    return result


@pytest.mark.parametrize('label', 'ABC')
@pytest.mark.parametrize('invalid', ['missing', 'duplicate', 'foreign', 'overlap'])
def test_each_candidate_rejects_invalid_partition(label, invalid):
    result = with_candidates()
    candidate = next(c.result for c in result.candidates if c.label == label)
    if invalid == 'missing':
        candidate.unplaced = []
    elif invalid == 'duplicate':
        candidate.unplaced.append(deepcopy(candidate.unplaced[0]))
    elif invalid == 'foreign':
        candidate.unplaced[0].package_id = '999'
    else:
        candidate.unplaced.append(UnplacedData(package_id='1', reason='OUT_OF_SPACE'))
    with pytest.raises(ValueError, match='package partition'):
        OptimizationClient._validated_remote(problem(), result)


@pytest.mark.parametrize('kind,status', [
    ('complete', 'COMPLETED'), ('partial', 'PARTIAL'), ('none', 'NO_SOLUTION')])
@pytest.mark.parametrize('candidates', [True, False])
def test_valid_complete_partial_and_no_solution(kind, status, candidates):
    result = with_candidates(kind) if candidates else layout(kind)
    assert OptimizationClient._validated_remote(problem(), result) is result
    handler = EngineExceptionHandler(job_service=Mock(), notification_service=Mock(), credit_client=Mock())
    assert handler.classify_result(result).value == status


def test_valid_mixed_candidates_keep_job_completed():
    result = with_candidates()
    result.candidates[1].result = layout('complete')
    result.candidates[2].result = layout('none')
    assert OptimizationClient._validated_remote(problem(), result) is result
    handler = EngineExceptionHandler(job_service=Mock(), notification_service=Mock(), credit_client=Mock())
    assert handler.classify_result(result).value == 'COMPLETED'


@pytest.mark.parametrize('label', 'ABC')
@pytest.mark.parametrize('violation', ['rotation', 'boundary', 'axle', 'overlap'])
def test_candidate_geometry_and_hc12_still_rejected(label, violation):
    request, result = problem(), with_candidates()
    candidate = next(c.result for c in result.candidates if c.label == label)
    if violation == 'rotation':
        candidate.placements[0].rotation_type = 1
    elif violation == 'boundary':
        candidate.placements[0].x = 590
    elif violation == 'axle':
        request.vehicle.front_axle_x = None
    else:
        candidate.placements.append(placement('2'))
        candidate.unplaced = []
    with pytest.raises(ValueError):
        OptimizationClient._validated_remote(request, result)
