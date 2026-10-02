from __future__ import annotations

from typing import Optional, List, Union, Dict, Any
from uuid import UUID
from pydantic import BaseModel, Field

from app.dto.optimization.engine.problem_request import VehicleData


class StopPackageData(BaseModel):
    """Thông tin kiện hàng thuộc một delivery stop."""
    id: Optional[Union[UUID, str]] = None
    volume: Optional[float] = None
    weight: Optional[float] = 0.0
    l: Optional[float] = None
    w: Optional[float] = None
    h: Optional[float] = None

    def get_volume(self) -> float:
        if self.volume is not None and self.volume > 0:
            return float(self.volume)
        if self.l is not None and self.w is not None and self.h is not None:
            return float(self.l * self.w * self.h)
        return 0.0

    def get_weight(self) -> float:
        return float(self.weight or 0.0)


class StopZoneInput(BaseModel):
    """Input cho từng delivery stop."""
    stop_id: Union[UUID, str, int]
    packages: List[Union[StopPackageData, Dict[str, Any]]] = Field(default_factory=list)
    sequence: Optional[int] = None


class ZoneData(BaseModel):
    """Thông số vùng (zone) trong xe cho một delivery stop."""
    stop_id: Union[UUID, str, int]
    zone_start_x: float
    zone_end_x: float
    zone_volume: float
    zone_weight_limit: float
    volume_ratio: Optional[float] = None
    zone_length: Optional[float] = None
    sequence: Optional[int] = None


class StopZoneCalculationRequest(BaseModel):
    vehicle: VehicleData
    stops: List[Union[StopZoneInput, Dict[str, Any]]]
    accessibility_buffer: Optional[float] = None


class StopZoneCalculationResponse(BaseModel):
    zones: List[ZoneData]
