from typing import Optional
from pydantic import BaseModel, Field

from app.constant.optimization.objective import OptimizationObjective


class OptimizationJobRequest(BaseModel):
    trip_id: str
    objective: OptimizationObjective = OptimizationObjective.MAX_VOLUME
    time_limit_sec: int = Field(default=60, ge=10, le=600)
    seed: Optional[int] = None
    algorithm_name: Optional[str] = None
