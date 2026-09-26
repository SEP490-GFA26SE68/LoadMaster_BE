from datetime import datetime
from typing import Optional, List
from pydantic import BaseModel, ConfigDict, model_validator

from app.dto.response.package_placement_response import PackagePlacementResponse


class LoadPlanResponse(BaseModel):
    id: Optional[int] = None
    job_id: Optional[int] = None
    plan_name: Optional[str] = None
    plan_version: Optional[int] = 1
    packed_items_count: Optional[int] = None
    volume_utilization: Optional[float] = None
    volume_utilization_percent: Optional[float] = None
    weight_utilization: Optional[float] = None
    is_approved: bool = False
    approved: bool = False
    approved_by_id: Optional[int] = None
    approved_by_user_id: Optional[int] = None
    approved_at: Optional[datetime] = None
    placements: List[PackagePlacementResponse] = []

    model_config = ConfigDict(populate_by_name=True, from_attributes=True)

    @model_validator(mode="after")
    def sync_v34_fields(self) -> "LoadPlanResponse":
        if self.volume_utilization_percent is not None and self.volume_utilization is None:
            self.volume_utilization = self.volume_utilization_percent
        elif self.volume_utilization is not None and self.volume_utilization_percent is None:
            self.volume_utilization_percent = self.volume_utilization

        if self.is_approved and not self.approved:
            self.approved = True
        elif self.approved and not self.is_approved:
            self.is_approved = True

        if self.approved_by_user_id is not None and self.approved_by_id is None:
            self.approved_by_id = self.approved_by_user_id
        elif self.approved_by_id is not None and self.approved_by_user_id is None:
            self.approved_by_user_id = self.approved_by_id

        return self
