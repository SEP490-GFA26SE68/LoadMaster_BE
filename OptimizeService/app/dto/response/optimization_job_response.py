from typing import Optional, Union
from datetime import datetime
from pydantic import BaseModel, ConfigDict, model_validator

from app.constant.optimization.job_status import OptimizationJobStatus
from app.constant.optimization.objective import OptimizationObjective


class OptimizationJobResponse(BaseModel):
    id: Optional[int] = None
    trip_id: Optional[Union[int, str]] = None
    algorithm_objective: Optional[str] = None
    execution_time_ms: Optional[int] = None
    status: Union[OptimizationJobStatus, str]

    # CamelCase aliases for API callers
    tripId: Optional[Union[int, str]] = None
    algorithmObjective: Optional[str] = None
    executionTimeMs: Optional[int] = None

    # Transitional compatibility fields
    job_uuid: Optional[Union[int, str]] = None
    objective: Optional[Union[OptimizationObjective, str]] = None
    time_limit_sec: Optional[int] = None
    computation_ms: Optional[int] = None
    created_at: Optional[datetime] = None

    model_config = ConfigDict(populate_by_name=True, from_attributes=True)

    @model_validator(mode="after")
    def sync_aliases(self) -> "OptimizationJobResponse":
        if self.trip_id is not None and self.tripId is None:
            self.tripId = self.trip_id
        elif self.tripId is not None and self.trip_id is None:
            self.trip_id = self.tripId

        if self.algorithm_objective is not None and self.algorithmObjective is None:
            self.algorithmObjective = self.algorithm_objective
        elif self.algorithmObjective is not None and self.algorithm_objective is None:
            self.algorithm_objective = self.algorithmObjective

        if self.execution_time_ms is not None and self.executionTimeMs is None:
            self.executionTimeMs = self.execution_time_ms
        elif self.executionTimeMs is not None and self.execution_time_ms is None:
            self.execution_time_ms = self.executionTimeMs

        return self
