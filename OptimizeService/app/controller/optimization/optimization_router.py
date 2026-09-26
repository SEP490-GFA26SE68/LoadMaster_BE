from typing import List, Any
from fastapi import APIRouter, Depends, BackgroundTasks, status
from sqlalchemy.orm import Session

from app.config.database import get_db
from app.dto.api_response import ApiResponse
from app.dto.request.optimization_request import OptimizationJobRequest
from app.dto.response.load_plan_response import LoadPlanResponse
from app.dto.response.optimization_job_response import OptimizationJobResponse
from app.service.auth.auth_dependency import require_role
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
    response_model=ApiResponse[Any],
)
async def submit_job(
    request: OptimizationJobRequest,
    background_tasks: BackgroundTasks,
    db: Session = Depends(get_db),
    job_service: OptimizationJobService = Depends(get_optimization_job_service),
    runner: AsyncOptimizationRunner = Depends(get_async_runner),
    current_user: dict = Depends(require_role("DISPATCHER", "ADMIN")),
) -> ApiResponse[Any]:
    """
    Tạo và khởi chạy tác vụ tối ưu xếp hàng (bất đồng bộ).
    Trả về 202 Accepted kèm job_id.
    """
    job = job_service.create_job(trip_id=request.trip_id, config=request, db=db)
    run_id = job.id if job.id is not None else job.job_uuid
    background_tasks.add_task(runner.run, run_id, db=db)
    returned_job_id = job.id if job.id is not None else job.job_uuid
    return ApiResponse.ok(
        data={"job_id": returned_job_id},
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
    current_user: dict = Depends(require_role("DISPATCHER", "ADMIN")),
) -> ApiResponse[OptimizationJobResponse]:
    """
    Lấy thông tin và trạng thái hiện tại của job theo ID hoặc UUID.
    """
    job = job_service.get_job(id, db)
    job_response = OptimizationJobResponse(
        id=job.id,
        trip_id=job.trip_id,
        algorithm_objective=getattr(job, "algorithm_objective", None) or getattr(job, "objective", None),
        execution_time_ms=getattr(job, "execution_time_ms", None),
        status=job.status,
        tripId=getattr(job, "tripId", job.trip_id),
        algorithmObjective=getattr(job, "algorithmObjective", None) or getattr(job, "algorithm_objective", None),
        executionTimeMs=getattr(job, "executionTimeMs", None) or getattr(job, "execution_time_ms", None),
        job_uuid=getattr(job, "_job_uuid", None) or job.id,
        objective=getattr(job, "objective", None) or getattr(job, "algorithm_objective", None),
        time_limit_sec=getattr(job, "time_limit_sec", 60),
        computation_ms=getattr(job, "computation_ms", None) or getattr(job, "execution_time_ms", None),
        created_at=getattr(job, "created_at", None),
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
    current_user: dict = Depends(require_role("DISPATCHER", "ADMIN")),
) -> ApiResponse[List[LoadPlanResponse]]:
    """
    Lấy danh sách các phương án xếp hàng (LoadPlan) được sinh ra từ job.
    """
    plans = load_plan_service.get_plans_for_job(id, db)
    return ApiResponse.ok(
        data=plans,
        message="Danh sách kế hoạch xếp hàng",
    )
