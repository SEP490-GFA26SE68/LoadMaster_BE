from typing import Optional, Union
from pydantic import BaseModel, ConfigDict, model_validator


class PackagePlacementResponse(BaseModel):
    id: Optional[int] = None
    load_plan_id: Optional[int] = None
    package_id: Union[int, str]
    loading_sequence: Optional[int] = None
    step_sequence: Optional[int] = None
    pos_x: float
    pos_y: float
    pos_z: float
    dim_x: Optional[float] = 0.0
    dim_y: Optional[float] = 0.0
    dim_z: Optional[float] = 0.0
    rotation_type: Optional[int] = 0

    model_config = ConfigDict(populate_by_name=True, from_attributes=True)

    @model_validator(mode="after")
    def sync_sequence(self) -> "PackagePlacementResponse":
        if self.loading_sequence is not None and self.step_sequence is None:
            self.step_sequence = self.loading_sequence
        elif self.step_sequence is not None and self.loading_sequence is None:
            self.loading_sequence = self.step_sequence
        elif self.loading_sequence is None and self.step_sequence is None:
            self.loading_sequence = 1
            self.step_sequence = 1
        return self
