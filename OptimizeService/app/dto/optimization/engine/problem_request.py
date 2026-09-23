from __future__ import annotations

from typing import Optional, Union, List
from uuid import UUID
from pydantic import BaseModel, Field


class VehicleData(BaseModel):
    """Thông số thùng xe cho Optimization Engine (mm hoặc m, đồng bộ theo đơn vị)."""
    inner_l: float
    inner_w: float
    inner_h: float
    max_payload_kg: float
    door_w: Optional[float] = None
    door_h: Optional[float] = None


class PackageData(BaseModel):
    """Thông số một kiện hàng gửi tới Optimization Engine."""
    id: Union[UUID, str]
    l: float
    w: float
    h: float
    weight: float
    allowed_rotations: List[Union[int, str]] = Field(default_factory=list)
    stop_index: int = 0
    max_stack_weight_kg: Optional[float] = None
    fragile: bool = False


class StopData(BaseModel):
    """Điểm giao hàng trong chuyến."""
    id: Union[UUID, str, int]
    sequence: int


class PinnedData(BaseModel):
    """Kiện hàng đã được cố định vị trí trước (nếu có)."""
    package_id: Union[UUID, str]
    pos_x: float
    pos_y: float
    pos_z: float
    rotation_type: Union[int, str] = 0


class ProblemRequest(BaseModel):
    """Payload gửi tới Optimization Engine để tính toán phương án xếp hàng."""
    vehicle: VehicleData
    packages: List[PackageData]
    stops: List[StopData] = Field(default_factory=list)
    pinned: List[PinnedData] = Field(default_factory=list)
    objective: str = "MAX_VOLUME_UTIL"  # "MAX_VOLUME_UTIL" | "AXLE_BALANCE" | "MIN_HEIGHT"
    time_limit_sec: int = 60
    seed: Optional[int] = None
