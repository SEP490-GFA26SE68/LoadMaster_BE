from typing import Optional, Union
from pydantic import BaseModel

from app.constant.optimization.job_status import OptimizationJobStatus


class JobStatusNotification(BaseModel):
    """
    Payload gửi qua WebSocket tới frontend khi job thay đổi trạng thái (S3-10).
    """
    job_uuid: str
    status: OptimizationJobStatus
    plan_id: Optional[Union[int, str]] = None
    message: Optional[str] = None
