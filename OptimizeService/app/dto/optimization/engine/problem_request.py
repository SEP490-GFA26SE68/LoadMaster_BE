from __future__ import annotations

from typing import Optional, Union, List
from uuid import UUID
from pydantic import BaseModel, Field, model_validator
from app.dto.optimization.engine.rotation_contract import RotationCode


class ObstacleData(BaseModel):
    """Fixed vehicle geometry: cm, minimum corner X/Y/Z, X length/Y width/Z height."""
    id: Union[int, str]
    name: str = ""
    type: str = Field(min_length=1)
    x: float = Field(ge=0, allow_inf_nan=False)
    y: float = Field(ge=0, allow_inf_nan=False)
    z: float = Field(ge=0, allow_inf_nan=False)
    length: float = Field(gt=0, allow_inf_nan=False)
    width: float = Field(gt=0, allow_inf_nan=False)
    height: float = Field(gt=0, allow_inf_nan=False)
    load_bearing: bool = Field(strict=True)

    # Shared geometry interface; these properties do not turn an obstacle into a package.
    @property
    def packed_l(self):
        return self.length

    @property
    def packed_w(self):
        return self.width

    @property
    def packed_h(self):
        return self.height


class VehicleData(BaseModel):
    """Thông số thùng xe và vật cản cố định theo cm; X dài, Y ngang, Z cao."""
    obstacles: List[ObstacleData] = Field(default_factory=list)
    inner_l: float
    inner_w: float
    inner_h: float
    max_payload_kg: float
    door_w: Optional[float] = None
    door_h: Optional[float] = None
    front_axle_limit_kg: Optional[float] = None
    rear_axle_limit_kg: Optional[float] = None

    # HC-12: Vị trí trục xe theo hệ tọa độ X của khoang hàng.
    # Đơn vị: cm. X = 0 tại mặt trước bên trong thùng.
    # Trục trước có thể nằm ở X âm.
    front_axle_x: Optional[float] = None
    rear_axle_x: Optional[float] = None

    # HC-12: Tải trên từng trục khi xe chưa chở hàng.
    # Đơn vị: kg.
    empty_front_axle_load_kg: Optional[float] = Field(
        default=None,
        ge=0,
    )
    empty_rear_axle_load_kg: Optional[float] = Field(
        default=None,
        ge=0,
    )

    max_cog_offset_ratio: float = Field(default=0.15, ge=0, le=0.5)


    @model_validator(mode="after")
    def validate_obstacles(self):
        keys = [str(o.id) for o in self.obstacles]
        if len(keys) != len(set(keys)):
            raise ValueError("Vehicle obstacle IDs must be unique")
        for o in self.obstacles:
            if o.x + o.length > self.inner_l or o.y + o.width > self.inner_w or o.z + o.height > self.inner_h:
                raise ValueError(f"Obstacle {o.id} exceeds vehicle interior bounds (cm)")
        return self


class PackageData(BaseModel):
    """Thông số một kiện hàng gửi tới Optimization Engine."""
    id: Union[UUID, str]
    l: float
    w: float
    h: float
    weight: float
    # Missing: original orientation only. Null is invalid; [] explicitly permits none.
    allowed_rotations: List[RotationCode] = Field(default_factory=lambda: [0])
    stop_index: int = Field(default=1, ge=1)
    max_stack_weight_kg: Optional[float] = None
    fragile: bool = False
    rotation_allowed: bool = True


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
    rotation_type: RotationCode = 0
    rotation_type: Union[int, str] = 0
    # Kích thước đã áp hướng xoay (cm). Vắng thì suy từ kích thước kiện và rotation_type.
    packed_l: Optional[float] = None
    packed_w: Optional[float] = None
    packed_h: Optional[float] = None


class ProblemRequest(BaseModel):
    """Payload gửi tới Optimization Engine để tính toán phương án xếp hàng."""
    vehicle: VehicleData
    packages: List[PackageData]
    stops: List[StopData] = Field(default_factory=list)
    pinned: List[PinnedData] = Field(default_factory=list)
    objective: str = "MAX_VOLUME_UTIL"  # "MAX_VOLUME_UTIL" | "AXLE_BALANCE" | "MIN_HEIGHT"
    time_limit_sec: int = 60
    seed: Optional[int] = None
