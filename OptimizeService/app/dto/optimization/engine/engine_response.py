from __future__ import annotations

from typing import List, Union, Optional
from uuid import UUID
from pydantic import BaseModel, Field, model_validator


class PlacementData(BaseModel):
    """Vị trí và hướng xoay của một kiện hàng được xếp thành công."""
    package_id: Union[int, str, UUID]
    x: float
    y: float
    z: float
    packed_l: Optional[float] = 0.0
    packed_w: Optional[float] = 0.0
    packed_h: Optional[float] = 0.0
    rotation_type: Union[int, str] = 0
    loading_sequence: Optional[int] = None
    step_sequence: int = 1

    @model_validator(mode="after")
    def sync_sequence(self) -> "PlacementData":
        if self.loading_sequence is not None and self.step_sequence == 1:
            self.step_sequence = self.loading_sequence
        elif self.loading_sequence is None:
            self.loading_sequence = self.step_sequence
        return self


class UnplacedData(BaseModel):
    """Kiện hàng không thể xếp kèm lý do."""
    package_id: Union[int, str, UUID]
    reason: Optional[str] = None
    reason_code: Optional[str] = None

    @model_validator(mode="after")
    def sync_reason(self) -> "UnplacedData":
        if self.reason is None and self.reason_code is not None:
            self.reason = self.reason_code
        elif self.reason_code is None and self.reason is not None:
            self.reason_code = self.reason
        elif self.reason is None and self.reason_code is None:
            self.reason = "UNPLACED"
            self.reason_code = "UNPLACED"
        return self


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
