from __future__ import annotations

from typing import Optional, List, Union
from uuid import UUID
from pydantic import BaseModel, Field


class PlacementData(BaseModel):
    """Vị trí và hướng xoay của một kiện hàng được xếp thành công."""
    package_id: Union[UUID, str]
    x: float
    y: float
    z: float
    packed_l: float
    packed_w: float
    packed_h: float
    rotation_type: Union[int, str] = 0
    step_sequence: int = 1
    stop_zone_id: Optional[Union[UUID, str, int]] = None
    # Kiện đã ghim: giữ nguyên vị trí và hướng của phương án trước.
    pinned: bool = False


class UnplacedData(BaseModel):
    """Kiện hàng không thể xếp kèm lý do."""
    package_id: Union[UUID, str]
    reason: str
    violated_constraints: List[str] = Field(default_factory=list)


class MetricsData(BaseModel):
    """Các chỉ số hiệu quả xếp hàng và thời gian tính toán."""
    volume_utilization: float = 0.0
    weight_utilization: float = 0.0
    packed_count: int = 0
    computation_ms: int = 0
    cog_x: Optional[float] = None
    cog_y: Optional[float] = None
    cog_z: Optional[float] = None
    front_axle_load: Optional[float] = None
    rear_axle_load: Optional[float] = None
    rehandling_count: int = 0


class EngineOptimizationResponse(BaseModel):
    """Kết quả trả về từ Optimization Engine."""
    placements: List[PlacementData] = Field(default_factory=list)
    unplaced: List[UnplacedData] = Field(default_factory=list)
    metrics: MetricsData = Field(default_factory=MetricsData)
    # Ba phương án ứng viên của một job (A · B · C), chỉ bộ giải nội bộ điền.
    # Không đưa ra JSON: lần chạy lại và engine ngoài vẫn là một kết quả.
    candidates: List["CandidatePlanData"] = Field(default_factory=list, exclude=True)
    # Thuật toán đã sinh ra cách xếp này (mã của PlanAlgorithm: EP_DBLF, GA, SA, EP_LUMO_R1_1,
    # LUMO_R1, LUMO_R1_1, LUMO_R2). Engine ngoài không điền thì để trống.
    algorithm_name: Optional[str] = None
    # Các thuật toán job đã chạy (kể cả cách không được chọn); chỉ để ghi log, không đưa ra JSON.
    algorithms_tried: List[str] = Field(default_factory=list, exclude=True)


class CandidatePlanData(BaseModel):
    """Một phương án ứng viên: nhãn (A | B | C), mục tiêu và kết quả xếp."""
    label: str
    objective: str
    result: EngineOptimizationResponse


EngineOptimizationResponse.model_rebuild()


# Alias cho phép sử dụng cả 2 tên gọi OptimizationResult và EngineOptimizationResponse
OptimizationResult = EngineOptimizationResponse
