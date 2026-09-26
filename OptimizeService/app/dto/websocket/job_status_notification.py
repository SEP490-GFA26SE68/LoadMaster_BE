from typing import Optional, Union
from pydantic import BaseModel, ConfigDict, model_validator

from app.constant.optimization.job_status import OptimizationJobStatus


class JobStatusNotification(BaseModel):
    """
    Payload gửi qua WebSocket tới frontend khi job thay đổi trạng thái (S3-10).
    """
    job_uuid: Optional[str] = None
    job_id: Optional[Union[int, str]] = None
    status: OptimizationJobStatus
    plan_id: Optional[Union[int, str]] = None
    message: Optional[str] = None

    model_config = ConfigDict(populate_by_name=True, from_attributes=True)

    @model_validator(mode="after")
    def sync_job_identifiers(self) -> "JobStatusNotification":
        if self.job_id is not None and self.job_uuid is None:
            self.job_uuid = str(self.job_id)
        elif self.job_uuid is not None and self.job_id is None:
            try:
                self.job_id = int(self.job_uuid)
            except ValueError:
                self.job_id = self.job_uuid
        return self
