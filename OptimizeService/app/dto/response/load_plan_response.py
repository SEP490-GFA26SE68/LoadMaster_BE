from typing import Optional, List
from pydantic import BaseModel

from app.dto.response.package_placement_response import PackagePlacementResponse


class LoadPlanResponse(BaseModel):
    id: Optional[int] = None
    plan_name: Optional[str] = None
    packed_items_count: Optional[int] = None
    volume_utilization: Optional[float] = None
    weight_utilization: Optional[float] = None
    approved: bool = False
    approved_by_id: Optional[int] = None
    placements: List[PackagePlacementResponse] = []
