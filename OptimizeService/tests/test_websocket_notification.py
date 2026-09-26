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

    def test_websocket_connect_with_bigint_job_id_and_receive_notification(self):
        """
        Acceptance Criteria 1 & 2:
          - WebSocket endpoint: /ws/jobs/{job_id} với job_id kiểu BIGINT
          - Client nhận đúng { job_id, status, plan_id, message }
        """
        client = TestClient(app)
        job_id = 987654321
        notification_service = get_job_notification_service()

        with client.websocket_connect(f"/ws/jobs/{job_id}") as websocket:
            notification_service.notify_status(
                job_id=job_id,
                status=OptimizationJobStatus.COMPLETED,
                plan_id=108,
                message="Optimization finished",
            )

            data = websocket.receive_json()
            assert data["job_id"] == job_id
            assert data["status"] == OptimizationJobStatus.COMPLETED.value
            assert data["plan_id"] == 108
            assert data["message"] == "Optimization finished"

    def test_broadcast_on_job_failed_and_timeout(self):
        """
        Acceptance Criteria 3:
          - Khi job failed / timeout -> broadcast tới tất cả client đang subscribe job đó
        """
        client = TestClient(app)
        notification_service = get_job_notification_service()

        # Test TIMEOUT
        job_timeout = 888111
        with client.websocket_connect(f"/ws/jobs/{job_timeout}") as ws_timeout:
            notification_service.notify_status(
                job_id=job_timeout,
                status=OptimizationJobStatus.TIMEOUT,
                message="Engine computation timed out",
            )
            data = ws_timeout.receive_json()
            assert data["job_id"] == job_timeout
            assert data["status"] == OptimizationJobStatus.TIMEOUT.value
            assert data["plan_id"] is None
            assert "timed out" in data["message"]

        # Test FAILED
        job_failed = 888222
        with client.websocket_connect(f"/ws/jobs/{job_failed}") as ws_failed:
            notification_service.notify_status(
                job_id=job_failed,
                status=OptimizationJobStatus.FAILED,
                message="Optimization failed due to engine connection error",
            )
            data = ws_failed.receive_json()
            assert data["job_id"] == job_failed
            assert data["status"] == OptimizationJobStatus.FAILED.value
            assert "failed" in data["message"]

    def test_broadcast_on_partial_and_no_solution(self):
        """Broadcast trạng thái PARTIAL (với plan_id) và NO_SOLUTION"""
        client = TestClient(app)
        notification_service = get_job_notification_service()

        # PARTIAL
        job_partial = 888333
        with client.websocket_connect(f"/ws/jobs/{job_partial}") as ws_partial:
            notification_service.notify_status(
                job_id=job_partial,
                status=OptimizationJobStatus.PARTIAL,
                plan_id=109,
                message="Partial solution found",
            )
            data = ws_partial.receive_json()
            assert data["job_id"] == job_partial
            assert data["status"] == OptimizationJobStatus.PARTIAL.value
            assert data["plan_id"] == 109

        # NO_SOLUTION
        job_no_sol = 888444
        with client.websocket_connect(f"/ws/jobs/{job_no_sol}") as ws_nosol:
            notification_service.notify_status(
                job_id=job_no_sol,
                status=OptimizationJobStatus.NO_SOLUTION,
                message="No feasible placement found",
            )
            data = ws_nosol.receive_json()
            assert data["job_id"] == job_no_sol
            assert data["status"] == OptimizationJobStatus.NO_SOLUTION.value
            assert data["plan_id"] is None

    def test_websocket_disconnect_cleans_up_connection(self):
        """Khi client disconnect, connection được tự động xóa khỏi service"""
        client = TestClient(app)
        job_id = 999123
        notification_service = get_job_notification_service()

        with client.websocket_connect(f"/ws/jobs/{job_id}"):
            assert len(notification_service.get_connections(job_id)) == 1

        # Ra khỏi context manager -> disconnected
        assert len(notification_service.get_connections(job_id)) == 0


class TestEngineAndRunnerWebSocketIntegration:
    """
    Test tích hợp giữa EngineExceptionHandler / AsyncOptimizationRunner và JobNotificationService
    """
    def test_engine_exception_handler_passes_plan_id_to_notification(self):
        """EngineExceptionHandler.handle_result phải truyền plan_id tới notify_status"""
        from app.service.optimize.engine_exception_handler import EngineExceptionHandler
        from app.dto.optimization.engine.engine_response import EngineOptimizationResponse, PlacementData, MetricsData
        from unittest.mock import MagicMock

        mock_notifier = MagicMock()
        mock_job_service = MagicMock()
        handler = EngineExceptionHandler(
            job_service=mock_job_service,
            notification_service=mock_notifier,
        )

        response = EngineOptimizationResponse(
            placements=[
                PlacementData(
                    package_id=uuid.uuid4(), x=0, y=0, z=0,
                    packed_l=1, packed_w=1, packed_h=1, rotation_type=0, step_sequence=1,
                )
            ],
            unplaced=[],
            metrics=MetricsData(computation_ms=150),
        )

        db_mock = MagicMock()
        handler.handle_result(
            result=response,
            job_id=777,
            db=db_mock,
            plan_id=999,
        )

        mock_notifier.notify_status.assert_called_once()
        call_kwargs = mock_notifier.notify_status.call_args.kwargs
        call_args = mock_notifier.notify_status.call_args.args
        assert (call_kwargs.get("plan_id") == 999) or (len(call_args) >= 3 and call_args[2] == 999)

    @pytest.mark.asyncio
    async def test_async_runner_propagates_persisted_plan_id_to_handler(self):
        """AsyncOptimizationRunner phải lấy plan.id từ persistence_service.save và truyền vào handle_result"""
        from app.service.optimize.async_optimization_runner import AsyncOptimizationRunner
        from app.dto.optimization.engine.engine_response import EngineOptimizationResponse, PlacementData, MetricsData

        mock_job_service = MagicMock()
        mock_client = AsyncMock()
        mock_handler = MagicMock()
        mock_persistence = MagicMock()
        saved_plan = MagicMock()
        saved_plan.id = 555
        mock_persistence.save.return_value = saved_plan

        runner = AsyncOptimizationRunner(
            job_service=mock_job_service,
            optimization_client=mock_client,
            engine_exception_handler=mock_handler,
            persistence_service=mock_persistence,
        )

        dummy_response = EngineOptimizationResponse(
            placements=[
                PlacementData(
                    package_id=uuid.uuid4(), x=0, y=0, z=0,
                    packed_l=1, packed_w=1, packed_h=1, rotation_type=0, step_sequence=1,
                )
            ],
            unplaced=[],
            metrics=MetricsData(computation_ms=100),
        )
        mock_client.solve.return_value = dummy_response

        db_mock = MagicMock()
        await runner.run(job_id=123, problem=MagicMock(), db=db_mock)

        # Persistence save được gọi với job_id 123
        mock_persistence.save.assert_called_once_with(123, dummy_response, db_mock)
        # Handler được gọi với plan_id=555
        mock_handler.handle_result.assert_called_once()
        assert mock_handler.handle_result.call_args.kwargs.get("plan_id") == 555
