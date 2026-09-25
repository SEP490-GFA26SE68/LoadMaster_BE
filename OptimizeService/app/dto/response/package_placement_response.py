from typing import Optional
from pydantic import BaseModel


class PackagePlacementResponse(BaseModel):
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
