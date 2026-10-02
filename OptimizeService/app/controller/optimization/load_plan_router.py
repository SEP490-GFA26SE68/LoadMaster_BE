from typing import Optional
from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy.orm import Session

from app.config.database import get_db
from app.dto.api_response import ApiResponse
from app.dto.response.load_plan_comparison_response import LoadPlanComparisonResponse
from app.dto.response.load_plan_response import LoadPlanResponse
from app.service.auth.auth_dependency import require_role
from app.service.optimize.load_plan_service import LoadPlanService

router = APIRouter(prefix="/api/v1/load-plans", tags=["Load Plans"])


def get_load_plan_service() -> LoadPlanService:
    return LoadPlanService()


@router.get(
    "/compare",
    response_model=ApiResponse[LoadPlanComparisonResponse],
)
def compare_plans(
    planId1: Optional[int] = Query(None, alias="planId1"),
    planId2: Optional[int] = Query(None, alias="planId2"),
    plan_id_1: Optional[int] = Query(None, alias="plan_id_1"),
    plan_id_2: Optional[int] = Query(None, alias="plan_id_2"),
    db: Session = Depends(get_db),
    service: LoadPlanService = Depends(get_load_plan_service),
    current_user: dict = Depends(require_role("DISPATCHER", "ADMIN")),
) -> ApiResponse[LoadPlanComparisonResponse]:
    """
    So sánh hai kế hoạch xếp hàng (S5b-07).
    Trả về COG diff, axle load diff, rehandling diff, và deadline feasibility.
    """
    target_p1 = planId1 if planId1 is not None else plan_id_1
    target_p2 = planId2 if planId2 is not None else plan_id_2
    if target_p1 is None or target_p2 is None:
        raise HTTPException(status_code=400, detail="Both planId1 and planId2 are required")
    comparison = service.compare_plans(plan_id_1=target_p1, plan_id_2=target_p2, db=db)
    return ApiResponse.ok(
        data=comparison,
        message="So sánh kế hoạch xếp hàng thành công",
    )


@router.post(
    "/{id}/approve",
    status_code=status.HTTP_200_OK,
    response_model=ApiResponse[LoadPlanResponse],
)
def approve_plan(
    id: int,
    db: Session = Depends(get_db),
    service: LoadPlanService = Depends(get_load_plan_service),
    current_user: dict = Depends(require_role("DISPATCHER", "ADMIN")),
) -> ApiResponse[LoadPlanResponse]:
    """
    Phê duyệt kế hoạch xếp hàng (S3-11).
    Chỉ DISPATCHER hoặc ADMIN có quyền phê duyệt.
    """
    user_id = current_user.get("user_id") or current_user.get("id") or current_user.get("sub")
    plan = service.approve_plan(plan_id=id, current_user_id=user_id, db=db)
    response_dto = service.to_response(plan, db)

    return ApiResponse.ok(
        data=response_dto,
        message="Kế hoạch xếp hàng đã được phê duyệt thành công",
    )
