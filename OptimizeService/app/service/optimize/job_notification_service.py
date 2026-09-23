from __future__ import annotations

import asyncio
import logging
from typing import Dict, List, Union, Any, Optional
from uuid import UUID

from app.constant.optimization.job_status import OptimizationJobStatus
from app.dto.websocket.job_status_notification import JobStatusNotification

logger = logging.getLogger(__name__)


class JobNotificationService:
    """
    Service quản lý kết nối WebSocket và broadcast thông báo trạng thái Job tới client (S3-10).
    """

    def __init__(self):
        # Map: job_uuid -> list[WebSocket]
        self._connections: Dict[str, List[Any]] = {}

    async def connect(self, job_uuid: Union[str, UUID], websocket: Any) -> None:
        key = str(job_uuid)
        if key not in self._connections:
            self._connections[key] = []
        if websocket not in self._connections[key]:
            self._connections[key].append(websocket)
        logger.debug(f"Client connected to job {key}. Active subscribers: {len(self._connections[key])}")

    async def disconnect(self, job_uuid: Union[str, UUID], websocket: Any) -> None:
        key = str(job_uuid)
        if key in self._connections and websocket in self._connections[key]:
            self._connections[key].remove(websocket)
            if not self._connections[key]:
                del self._connections[key]
        logger.debug(f"Client disconnected from job {key}")

    def get_connections(self, job_uuid: Union[str, UUID]) -> List[Any]:
        return self._connections.get(str(job_uuid), [])

    async def notify(
        self,
        job_uuid: Union[str, UUID],
        notification: Union[JobStatusNotification, dict],
    ) -> None:
        """
        Broadcast thông báo tới toàn bộ clients đang subscribe job_uuid.
        """
        key = str(job_uuid)
        clients = list(self._connections.get(key, []))
        if not clients:
            return

        payload = (
            notification.model_dump(mode="json")
            if hasattr(notification, "model_dump")
            else notification
        )

        dead_clients = []
        for client in clients:
            try:
                await client.send_json(payload)
            except Exception as e:
                logger.warning(f"Failed to send websocket message to client for job {key}: {e}")
                dead_clients.append(client)

        for dead in dead_clients:
            await self.disconnect(key, dead)

    def notify_status(
        self,
        job_uuid: Union[str, UUID],
        status: OptimizationJobStatus,
        plan_id: Optional[Union[int, str]] = None,
        message: Optional[str] = None,
        computation_ms: Optional[int] = None,
    ) -> None:
        """
        Helper method đồng bộ/bất đồng bộ để gọi từ EngineExceptionHandler hoặc runner.
        Tự động tạo task nếu loop đang chạy hoặc chạy sync nếu cần.
        """
        notification = JobStatusNotification(
            job_uuid=str(job_uuid),
            status=status,
            plan_id=plan_id,
            message=message or f"Job status updated to {status.value if hasattr(status, 'value') else status}",
        )
        try:
            loop = asyncio.get_running_loop()
            loop.create_task(self.notify(job_uuid, notification))
        except RuntimeError:
            asyncio.run(self.notify(job_uuid, notification))


# Singleton instance cho toàn app
_job_notification_service = JobNotificationService()


def get_job_notification_service() -> JobNotificationService:
    return _job_notification_service
