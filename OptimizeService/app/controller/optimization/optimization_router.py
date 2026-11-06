from typing import List, Any
from fastapi import APIRouter, Depends, BackgroundTasks, status
from sqlalchemy.orm import Session

from app.config.database import get_db
from app.dto.api_response import ApiResponse
from app.dto.request.optimization_request import OptimizationDispatchRequest
from app.dto.response.optimization_dispatch_response import OptimizationDispatchResponse
from app.dto.response.load_plan_response import LoadPlanResponse
from app.dto.response.optimization_job_response import OptimizationJobResponse
from app.service.auth.auth_dependency import require_optimization_service
from app.service.optimize.async_optimization_runner import AsyncOptimizationRunner
from app.service.optimize.load_plan_service import LoadPlanService
from app.service.optimize.optimization_job_service import OptimizationJobService

router = APIRouter(prefix="/api/v1/optimization", tags=["Optimization"])


def get_optimization_job_service() -> OptimizationJobService:
    return OptimizationJobService()


def get_load_plan_service() -> LoadPlanService:
    return LoadPlanService()


def get_async_runner() -> AsyncOptimizationRunner:
    return AsyncOptimizationRunner()


@router.post(
    "/jobs",
    status_code=status.HTTP_202_ACCEPTED,
    response_model=ApiResponse[OptimizationDispatchResponse],
)
async def submit_job(
    request: OptimizationDispatchRequest,
    background_tasks: BackgroundTasks,
    db: Session = Depends(get_db),
    job_service: OptimizationJobService = Depends(get_optimization_job_service),
    runner: AsyncOptimizationRunner = Depends(get_async_runner),
    service_claims: dict = Depends(require_optimization_service),
) -> ApiResponse[OptimizationDispatchResponse]:
    """
    Tạo và khởi chạy tác vụ tối ưu xếp hàng (bất đồng bộ).
    Trả về 202 Accepted kèm job_id.
    """
    from app.service.optimize.authorized_job_dispatch import require_authorized_job
    job = require_authorized_job(request.job_uuid, request.trip_id, db)
    # Keep PENDING until a worker actually starts. Response loss / failed scheduling can be retried.
    # Concurrent retries may schedule duplicate tasks; the worker CAS admits exactly one execution.
    if job.status == "PENDING":
        background_tasks.add_task(runner.run, request.job_uuid)
        dispatch_state = "ACCEPTED"
    elif job.status == "RUNNING":
        dispatch_state = "ALREADY_RUNNING"
    else:
        dispatch_state = "ALREADY_TERMINAL"

    return ApiResponse.ok(
        data=OptimizationDispatchResponse(
            job_id=job.job_uuid, status=job.status, dispatch_state=dispatch_state,
        ),
        message="Tác vụ tối ưu đã được khởi tạo",
    )


@router.get(
    "/jobs/{id}",
    response_model=ApiResponse[OptimizationJobResponse],
)
async def get_job(
    id: str,
    db: Session = Depends(get_db),
    job_service: OptimizationJobService = Depends(get_optimization_job_service),
    service_claims: dict = Depends(require_optimization_service),
) -> ApiResponse[OptimizationJobResponse]:
    """
    Lấy thông tin và trạng thái hiện tại của job theo ID hoặc UUID.
    """
    job = job_service.get_job(id, db)
    job_response = OptimizationJobResponse(
        id=job.id,
        job_uuid=job.job_uuid,
        trip_id=job.trip_id,
        objective=job.objective,
        time_limit_sec=job.time_limit_sec,
        status=job.status,
        computation_ms=job.computation_ms,
        created_at=job.created_at,
    )
    return ApiResponse.ok(
        data=job_response,
        message="Thông tin tác vụ tối ưu",
    )


@router.get(
    "/jobs/{id}/plans",
    response_model=ApiResponse[List[LoadPlanResponse]],
)
async def get_job_plans(
    id: str,
    db: Session = Depends(get_db),
    load_plan_service: LoadPlanService = Depends(get_load_plan_service),
    service_claims: dict = Depends(require_optimization_service),
) -> ApiResponse[List[LoadPlanResponse]]:
    """
    Lấy danh sách các phương án xếp hàng (LoadPlan) được sinh ra từ job.
    """
    plans = load_plan_service.get_plans_for_job(id, db)
    return ApiResponse.ok(
        data=plans,
        message="Danh sách kế hoạch xếp hàng",
    )
