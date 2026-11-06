from typing import List, Any
from fastapi import APIRouter, Depends, BackgroundTasks, status
from sqlalchemy.orm import Session

from app.config.database import get_db
from app.dto.api_response import ApiResponse
from app.dto.optimization.engine.problem_request import PinnedData
from app.dto.request.optimization_request import OptimizationJobRequest
from app.exception.app_exception import AppException
from app.exception.error_code import ErrorCode
from app.dto.response.load_plan_response import LoadPlanResponse
from app.dto.response.optimization_job_response import OptimizationJobResponse
from app.service.auth.auth_dependency import require_optimization_service
from app.service.optimize.async_optimization_runner import AsyncOptimizationRunner
from app.service.optimize.load_plan_service import LoadPlanService
from app.service.optimize.optimization_job_service import OptimizationJobService
from app.service.optimize.pinned_set_validator import pinned_set_issues

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
        service_claims: dict = Depends(require_optimization_service),
) -> ApiResponse[Any]:
    """
    Tạo và khởi chạy tác vụ tối ưu xếp hàng (bất đồng bộ).
    Trả về 202 Accepted kèm job_id.
    """
    problem = None
    if request.pinned_placements:
        # Giữ kiện đã ghim: kiểm bộ ghim trước khi tạo job, hỏng thì không chạy và không trừ credit
        objective = request.objective.value if hasattr(request.objective, "value") else str(request.objective)
        problem = runner.build_problem_request_for_trip(request.trip_id, objective, request.time_limit_sec, db)
        problem.pinned = [
            PinnedData(
                package_id=str(pin.package_id),
                pos_x=pin.pos_x,
                pos_y=pin.pos_y,
                pos_z=pin.pos_z,
                rotation_type=pin.rotation_type,
                packed_l=pin.packed_length,
                packed_w=pin.packed_width,
                packed_h=pin.packed_height,
            )
            for pin in request.pinned_placements
        ]
        issues = pinned_set_issues(problem)
        if issues:
            raise AppException(
                ErrorCode.PINNED_SET_INVALID,
                errors=[issue.as_dict() for issue in issues],
            )

    job = job_service.create_job(trip_id=request.trip_id, config=request, db=db)

    if problem is None:
        background_tasks.add_task(runner.run, job.job_uuid)
    else:
        background_tasks.add_task(runner.run, job.job_uuid, problem)

    return ApiResponse.ok(
        data={"job_id": job.job_uuid},
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
