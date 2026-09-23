from __future__ import annotations

from typing import List, Union
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


class UnplacedData(BaseModel):
    """Kiện hàng không thể xếp kèm lý do."""
    package_id: Union[UUID, str]
    reason: str


class MetricsData(BaseModel):
    """Các chỉ số hiệu quả xếp hàng và thời gian tính toán."""
    volume_utilization: float = 0.0
    weight_utilization: float = 0.0
    packed_count: int = 0
    computation_ms: int = 0


class EngineOptimizationResponse(BaseModel):
    """Kết quả trả về từ Optimization Engine."""
    placements: List[PlacementData] = Field(default_factory=list)
    unplaced: List[UnplacedData] = Field(default_factory=list)
    metrics: MetricsData = Field(default_factory=MetricsData)


# Alias cho phép sử dụng cả 2 tên gọi OptimizationResult và EngineOptimizationResponse
OptimizationResult = EngineOptimizationResponse
