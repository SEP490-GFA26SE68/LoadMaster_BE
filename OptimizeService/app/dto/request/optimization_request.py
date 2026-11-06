
from typing import Optional

from pydantic import BaseModel, Field

from app.constant.optimization.objective import OptimizationObjective


class OptimizationJobRequest(BaseModel):
    # Khớp với trips.id BIGINT trong PostgreSQL
    trip_id: int = Field(gt=0)

    # Required by the submit endpoint; optional in this shared DTO for old read/unit fixtures.
    job_uuid: Optional[str] = None

    objective: OptimizationObjective = OptimizationObjective.MAX_VOLUME

    time_limit_sec: int = Field(default=60, ge=10, le=600)

    seed: Optional[int] = None

    algorithm_name: Optional[str] = None

    company_id: Optional[str] = None

    subscription_tier: Optional[str] = "BASIC"


class OptimizationDispatchRequest(OptimizationJobRequest):
    # The HTTP admission contract requires the identity reserved by Spring.
    # Shared legacy fixtures may still use OptimizationJobRequest for non-submit calls.
    job_uuid: str = Field(min_length=1, max_length=64)
