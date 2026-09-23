"""
Tests for WebSocket Notification — S3-10

Seams under test:
  - JobNotificationService.connect(job_uuid, websocket)
  - JobNotificationService.disconnect(job_uuid, websocket)
  - JobNotificationService.notify(job_uuid, notification)
  - WebSocket endpoint: /ws/jobs/{job_uuid} in FastAPI app

Acceptance Criteria:
  - WebSocket endpoint: ws://host/ws/jobs/{job_uuid}
  - Client subscribe -> nhận { job_uuid, status, plan_id, message } khi job xong
  - Khi job completed/failed/timeout -> broadcast tới tất cả client đang subscribe job đó
"""
import uuid
import pytest
from unittest.mock import AsyncMock, MagicMock
from starlette.testclient import TestClient

from app.main import app
from app.constant.optimization.job_status import OptimizationJobStatus
from app.dto.websocket.job_status_notification import JobStatusNotification
from app.service.optimize.job_notification_service import (
    JobNotificationService,
    get_job_notification_service,
)


class TestJobNotificationServiceUnit:
    @pytest.mark.asyncio
    async def test_connect_and_disconnect(self):
        """Kiểm tra quản lý kết nối thêm/xóa WebSocket client theo job_uuid"""
        service = JobNotificationService()
        ws1 = MagicMock()
        ws2 = MagicMock()
        job_uuid = str(uuid.uuid4())

        await service.connect(job_uuid, ws1)
        await service.connect(job_uuid, ws2)
        assert len(service.get_connections(job_uuid)) == 2

        await service.disconnect(job_uuid, ws1)
        assert len(service.get_connections(job_uuid)) == 1
        assert service.get_connections(job_uuid)[0] == ws2

        await service.disconnect(job_uuid, ws2)
        assert len(service.get_connections(job_uuid)) == 0

    @pytest.mark.asyncio
    async def test_notify_broadcasts_to_all_subscribers_of_target_job(self):
        """Gửi thông báo tới toàn bộ client subscribe job_uuid chỉ định, không gửi sang job_uuid khác"""
        service = JobNotificationService()
        job1 = str(uuid.uuid4())
        job2 = str(uuid.uuid4())

        ws1_job1 = MagicMock()
        ws1_job1.send_json = AsyncMock()
        ws2_job1 = MagicMock()
        ws2_job1.send_json = AsyncMock()
        ws_job2 = MagicMock()
        ws_job2.send_json = AsyncMock()

        await service.connect(job1, ws1_job1)
        await service.connect(job1, ws2_job1)
        await service.connect(job2, ws_job2)

        notification = JobStatusNotification(
            job_uuid=job1,
            status=OptimizationJobStatus.COMPLETED,
            plan_id=101,
            message="Optimization succeeded",
        )

        await service.notify(job1, notification)

        # Cả 2 client của job1 đều nhận được
        ws1_job1.send_json.assert_awaited_once_with(notification.model_dump(mode="json"))
        ws2_job1.send_json.assert_awaited_once_with(notification.model_dump(mode="json"))

        # Client của job2 không bị nhận nhầm
        ws_job2.send_json.assert_not_called()

    @pytest.mark.asyncio
    async def test_notify_handles_dead_connection_gracefully(self):
        """Khi một websocket client bị ngắt kết nối đột ngột, service tự loại bỏ mà không raise lỗi"""
        service = JobNotificationService()
        job = str(uuid.uuid4())

        ws_dead = MagicMock()
        ws_dead.send_json = AsyncMock(side_effect=Exception("Connection closed"))
        ws_alive = MagicMock()
        ws_alive.send_json = AsyncMock()

        await service.connect(job, ws_dead)
        await service.connect(job, ws_alive)

        notification = JobStatusNotification(
            job_uuid=job,
            status=OptimizationJobStatus.FAILED,
            message="Engine error",
        )

        await service.notify(job, notification)

        # Client còn sống vẫn nhận được tin nhắn
        ws_alive.send_json.assert_awaited_once_with(notification.model_dump(mode="json"))
        # Client chết đã bị xóa khỏi danh sách
        assert ws_dead not in service.get_connections(job)


class TestWebSocketEndpointIntegration:
    def test_websocket_connect_and_receive_notification(self):
        """
        Integration test:
          1. TestClient kết nối WebSocket tới /ws/jobs/{job_uuid}
          2. Server gọi notify()
          3. Client nhận đúng JSON payload
        """
        client = TestClient(app)
        job_uuid = str(uuid.uuid4())
        notification_service = get_job_notification_service()

        with client.websocket_connect(f"/ws/jobs/{job_uuid}") as websocket:
            notification = JobStatusNotification(
                job_uuid=job_uuid,
                status=OptimizationJobStatus.COMPLETED,
                plan_id=42,
                message="Packing completed with 100% volume utilization",
            )

            # Gửi thông báo từ backend
            import asyncio
            asyncio.run(notification_service.notify(job_uuid, notification))

            # Nhận trên WebSocket client
            data = websocket.receive_json()
            assert data["job_uuid"] == job_uuid
            assert data["status"] == OptimizationJobStatus.COMPLETED.value
            assert data["plan_id"] == 42
            assert "100%" in data["message"]
