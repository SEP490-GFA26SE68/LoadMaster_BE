from typing import Optional
from pydantic import BaseModel, Field, ConfigDict


class PackagePlacementResponse(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    id: Optional[int] = None
    package_id: str
    step_sequence: int
    pos_x: float
    pos_y: float
    pos_z: float
    dim_x: float
    dim_y: float
    dim_z: float
    rotation_type: Optional[int] = 0
    stop_zone_id: Optional[int] = Field(default=None, alias="stopZoneId")
    stop_zone_name: Optional[str] = Field(default=None, alias="stopZoneName")
