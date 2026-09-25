from fastapi import APIRouter, Depends, status
from sqlalchemy.orm import Session

from app.config.database import get_db
from app.dto.api_response import ApiResponse
from app.dto.response.load_plan_response import LoadPlanResponse
from app.service.auth.auth_dependency import require_role
from app.service.optimize.load_plan_service import LoadPlanService

router = APIRouter(prefix="/api/v1/load-plans", tags=["Load Plans"])


def get_load_plan_service() -> LoadPlanService:
    return LoadPlanService()


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
