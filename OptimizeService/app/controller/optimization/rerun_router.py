"""
Chạy lại tối ưu giữ kiện đã ghim (Spring Boot gọi bằng service token).

Java tạo job và phương án mới trong cùng một giao dịch nên job chưa có trong DB lúc gọi sang:
dịch vụ dựng bài toán thẳng từ chuyến, trả kết quả, không lưu gì và không trừ credit
(Java đã trừ credit cho lần chạy lại).

Đơn vị: cm, kg. Toạ độ và kích thước trả về làm tròn tới cm nguyên vì bảng
package_placements phía Java khai số nguyên.
"""
from typing import Any, List, Optional

from fastapi import APIRouter, Depends
from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel
from sqlalchemy.orm import Session

from app.config.database import get_db
from app.dto.api_response import ApiResponse
from app.dto.optimization.engine.engine_response import EngineOptimizationResponse
from app.dto.optimization.engine.problem_request import PinnedData
from app.service.auth.auth_dependency import require_optimization_service
from app.service.optimize.async_optimization_runner import AsyncOptimizationRunner
from app.service.optimize.optimization_client import OptimizationClient

router = APIRouter(prefix="/api/v1/optimize", tags=["Optimization"])


class _CamelModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)


class PinnedPlacementIn(_CamelModel):
    placement_id: Optional[int] = None
    package_id: int
    pos_x: float
    pos_y: float
    pos_z: float
    packed_length: Optional[float] = None
    packed_width: Optional[float] = None
    packed_height: Optional[float] = None
    rotation_type: int = 0
    step_sequence: Optional[int] = None


class RerunRequest(_CamelModel):
    job_uuid: Optional[str] = None
    trip_id: int = Field(gt=0)
    parent_plan_id: Optional[int] = None
    objective: Optional[str] = None
    time_limit_sec: Optional[int] = Field(default=60, ge=1, le=600)
    seed: Optional[int] = None
    pinned_placements: List[PinnedPlacementIn] = Field(default_factory=list)


def get_runner() -> AsyncOptimizationRunner:
    # Chỉ dùng phần dựng bài toán; không gọi Java để trừ credit.
    return AsyncOptimizationRunner(credit_client=False, persistence_service=False)


def get_solver() -> OptimizationClient:
    return OptimizationClient()


def _id(value: Any) -> Any:
    text = str(value)
    return int(text) if text.isdigit() else text


def _cm(value: Any) -> int:
    return int(round(float(value)))


def to_rerun_result(result: EngineOptimizationResponse) -> dict[str, Any]:
    """Kết quả theo tên trường của OptimizationResultDto phía Java (camelCase)."""
    metrics = result.metrics
    return {
        "packedItemsCount": metrics.packed_count,
        "volumeUtilization": metrics.volume_utilization,
        "weightUtilization": metrics.weight_utilization,
        "computationMs": metrics.computation_ms,
        "cogX": metrics.cog_x,
        "cogY": metrics.cog_y,
        "cogZ": metrics.cog_z,
        "frontAxleLoad": metrics.front_axle_load,
        "rearAxleLoad": metrics.rear_axle_load,
        "rehandlingCount": metrics.rehandling_count,
        "placements": [
            {
                "packageId": _id(p.package_id),
                "posX": _cm(p.x),
                "posY": _cm(p.y),
                "posZ": _cm(p.z),
                "packedLength": _cm(p.packed_l),
                "packedWidth": _cm(p.packed_w),
                "packedHeight": _cm(p.packed_h),
                "rotationType": int(p.rotation_type) if str(p.rotation_type).isdigit() else 0,
                "stepSequence": p.step_sequence,
                "pinned": bool(p.pinned),
                "stopZoneId": _id(p.stop_zone_id) if p.stop_zone_id is not None else None,
            }
            for p in result.placements
        ],
        "unplaced": [
            {"packageId": _id(u.package_id), "reason": u.reason}
            for u in result.unplaced
        ],
    }


@router.post(
    "/rerun",
    response_model=ApiResponse[Any],
)
async def rerun(
    request: RerunRequest,
    db: Session = Depends(get_db),
    runner: AsyncOptimizationRunner = Depends(get_runner),
    solver: OptimizationClient = Depends(get_solver),
    service_claims: dict = Depends(require_optimization_service),
) -> ApiResponse[Any]:
    problem = runner.build_problem_request_for_trip(
        request.trip_id,
        request.objective,
        request.time_limit_sec,
        db,
    )
    problem.seed = request.seed
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

    result = await solver.solve(problem)

    return ApiResponse.ok(
        data=to_rerun_result(result),
        message="Chạy lại tối ưu giữ kiện đã ghim thành công",
    )
