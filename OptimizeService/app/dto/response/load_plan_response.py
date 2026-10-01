from typing import Optional, List
from pydantic import BaseModel, Field, ConfigDict

from app.dto.response.package_placement_response import PackagePlacementResponse


class LoadPlanResponse(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    id: Optional[int] = None
    plan_name: Optional[str] = None
    packed_items_count: Optional[int] = None
    volume_utilization: Optional[float] = None
    weight_utilization: Optional[float] = None
    approved: bool = False
    approved_by_id: Optional[int] = None
    cog_x: Optional[float] = Field(default=None, alias="cogX")
    cog_y: Optional[float] = Field(default=None, alias="cogY")
    cog_z: Optional[float] = Field(default=None, alias="cogZ")
    front_axle_load: Optional[float] = Field(default=None, alias="frontAxleLoad")
    rear_axle_load: Optional[float] = Field(default=None, alias="rearAxleLoad")
    rehandling_count: Optional[int] = Field(default=0, alias="rehandlingCount")
    placements: List[PackagePlacementResponse] = []
