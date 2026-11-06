"""
Credit của một lần chạy tối ưu: giữ 1 credit trước khi chạy, chỉ giữ lượt trừ khi lưu được
phương án (COMPLETED / PARTIAL); NO_SOLUTION hoặc lỗi thì hoàn đúng một lần.
"""
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock

import pytest

from app.constant.optimization.job_status import OptimizationJobStatus
from app.dto.optimization.engine.engine_response import (
    EngineOptimizationResponse,
    MetricsData,
    PlacementData,
    UnplacedData,
)
from app.exception.app_exception import AppException
from app.exception.error_code import ErrorCode
from app.service.optimize.async_optimization_runner import AsyncOptimizationRunner
from app.service.optimize.engine_exception_handler import EngineExceptionHandler

JOB_UUID = "11111111-2222-3333-4444-555555555555"


def _placed(unplaced=0):
    return EngineOptimizationResponse(
        placements=[PlacementData(package_id="1", x=0, y=0, z=0, packed_l=10, packed_w=10, packed_h=10)],
        unplaced=[UnplacedData(package_id="2", reason="NO_SPACE") for _ in range(unplaced)],
        metrics=MetricsData(packed_count=1, computation_ms=5),
    )


def _nothing():
    return EngineOptimizationResponse(
        placements=[],
        unplaced=[UnplacedData(package_id="1", reason="NO_SPACE")],
        metrics=MetricsData(packed_count=0, computation_ms=5),
    )


def _runner(solve):
    job_service = MagicMock()
    job_service.get_job.return_value = SimpleNamespace(
        company_id="7", subscription_tier=None, algorithm_tier=None
    )
    credit = MagicMock()
    credit.deduct_credit = AsyncMock(return_value=True)
    credit.refund_credit = AsyncMock(return_value=True)
    client = MagicMock()
    client.solve = solve
    handler = EngineExceptionHandler(
        job_service=job_service, notification_service=False, credit_client=None
    )
    # Handler tự hoàn credit khi FAILED / TIMEOUT qua lối khác; ở đây chỉ đếm lượt của runner.
    handler.credit_client = None
    runner = AsyncOptimizationRunner(
        job_service=job_service,
        optimization_client=client,
        engine_exception_handler=handler,
        persistence_service=MagicMock(),
        session_factory=MagicMock,
        credit_client=credit,
    )
    return runner, credit, job_service


def _last_status(job_service):
    call = job_service.update_status.call_args_list[-1]
    return call.kwargs.get("status", call.args[1] if len(call.args) > 1 else None)


@pytest.mark.asyncio
async def test_completed_run_keeps_one_deduction():
    runner, credit, job_service = _runner(AsyncMock(return_value=_placed()))
    await runner.run(JOB_UUID, problem=MagicMock(), db=MagicMock())
    credit.deduct_credit.assert_awaited_once_with("7", JOB_UUID)
    credit.refund_credit.assert_not_awaited()
    assert _last_status(job_service) == OptimizationJobStatus.COMPLETED


@pytest.mark.asyncio
async def test_partial_run_keeps_one_deduction():
    runner, credit, job_service = _runner(AsyncMock(return_value=_placed(unplaced=1)))
    await runner.run(JOB_UUID, problem=MagicMock(), db=MagicMock())
    credit.deduct_credit.assert_awaited_once()
    credit.refund_credit.assert_not_awaited()
    assert _last_status(job_service) == OptimizationJobStatus.PARTIAL


@pytest.mark.asyncio
async def test_no_solution_refunds_exactly_once():
    runner, credit, job_service = _runner(AsyncMock(return_value=_nothing()))
    await runner.run(JOB_UUID, problem=MagicMock(), db=MagicMock())
    credit.deduct_credit.assert_awaited_once()
    credit.refund_credit.assert_awaited_once_with("7", JOB_UUID)
    assert _last_status(job_service) == OptimizationJobStatus.NO_SOLUTION


@pytest.mark.asyncio
async def test_failed_run_refunds_exactly_once():
    runner, credit, job_service = _runner(AsyncMock(side_effect=RuntimeError("solver crash")))
    await runner.run(JOB_UUID, problem=MagicMock(), db=MagicMock())
    credit.deduct_credit.assert_awaited_once()
    credit.refund_credit.assert_awaited_once_with("7", JOB_UUID)
    assert _last_status(job_service) == OptimizationJobStatus.FAILED


@pytest.mark.asyncio
async def test_insufficient_credits_fails_job_without_running():
    solve = AsyncMock(return_value=_placed())
    runner, credit, job_service = _runner(solve)
    credit.deduct_credit = AsyncMock(side_effect=AppException(ErrorCode.INSUFFICIENT_CREDITS))
    await runner.run(JOB_UUID, problem=MagicMock(), db=MagicMock())
    solve.assert_not_awaited()
    credit.refund_credit.assert_not_awaited()
    assert _last_status(job_service) == OptimizationJobStatus.FAILED
