from typing import Literal
from pydantic import BaseModel
from app.constant.optimization.job_status import OptimizationJobStatus


class OptimizationDispatchResponse(BaseModel):
    # Always the identity reserved and billed by Spring, never a new UUID.
    job_id: str
    status: OptimizationJobStatus
    dispatch_state: Literal["ACCEPTED", "ALREADY_RUNNING", "ALREADY_TERMINAL"]
