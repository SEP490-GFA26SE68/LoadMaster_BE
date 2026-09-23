from typing import Optional
from datetime import datetime
from pydantic import BaseModel

from app.constant.optimization.job_status import OptimizationJobStatus
from app.constant.optimization.objective import OptimizationObjective


class OptimizationJobResponse(BaseModel):
    id: Optional[int] = None
    job_uuid: str
    trip_id: str
    objective: Optional[OptimizationObjective] = None
    time_limit_sec: Optional[int] = None
    status: OptimizationJobStatus
    computation_ms: Optional[int] = None
    created_at: Optional[datetime] = None
