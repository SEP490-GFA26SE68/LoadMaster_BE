"""
Tests for S3-05 · DTO: ProblemRequest + OptimizationResult

Seams under test:
  - ProblemRequest and nested schemas (VehicleData, PackageData, StopData, PinnedData)
  - EngineOptimizationResponse (OptimizationResult) and nested schemas (PlacementData, UnplacedData, MetricsData)
  - JobStatusNotification schema
  - JSON serialization / deserialization roundtrip
"""
import uuid
import pytest
from pydantic import ValidationError

from app.constant.optimization.job_status import OptimizationJobStatus
from app.dto.optimization.engine.problem_request import (
    ProblemRequest,
    VehicleData,
    PackageData,
    StopData,
    PinnedData,
)
from app.dto.optimization.engine.engine_response import (
    EngineOptimizationResponse,
    OptimizationResult,
    PlacementData,
    UnplacedData,
    MetricsData,
)
from app.dto.websocket.job_status_notification import JobStatusNotification


class TestProblemRequestDto:
    def test_problem_request_valid_construction_and_json_roundtrip(self):
        """Tạo ProblemRequest với đầy đủ trường dữ liệu và kiểm tra JSON roundtrip"""
        pkg_id = uuid.uuid4()
        req = ProblemRequest(
            vehicle=VehicleData(
                inner_l=6000.0,
                inner_w=2400.0,
                inner_h=2400.0,
                max_payload_kg=5000.0,
                door_w=2400.0,
                door_h=2400.0,
            ),
            packages=[
                PackageData(
                    id=pkg_id,
                    l=1000.0,
                    w=800.0,
                    h=600.0,
                    weight=50.0,
                    allowed_rotations=[0, 1, 2],
                    stop_index=1,
                    max_stack_weight_kg=200.0,
                    fragile=False,
                )
            ],
            stops=[StopData(id=1, sequence=1)],
            pinned=[PinnedData(package_id=pkg_id, pos_x=0.0, pos_y=0.0, pos_z=0.0, rotation_type=0)],
            objective="MAX_VOLUME_UTIL",
            time_limit_sec=60,
            seed=42,
        )

        # JSON roundtrip
        json_str = req.model_dump_json()
        restored = ProblemRequest.model_validate_json(json_str)

        assert restored.vehicle.inner_l == 6000.0
        assert restored.packages[0].id == pkg_id
        assert restored.packages[0].l == 1000.0
        assert restored.stops[0].sequence == 1
        assert restored.pinned[0].pos_x == 0.0
        assert restored.objective == "MAX_VOLUME_UTIL"
        assert restored.seed == 42

    def test_problem_request_defaults(self):
        """ProblemRequest có các giá trị mặc định hợp lý (stops=[], pinned=[], time_limit_sec=60)"""
        req = ProblemRequest(
            vehicle=VehicleData(inner_l=5.0, inner_w=2.0, inner_h=2.0, max_payload_kg=3000.0),
            packages=[],
        )
        assert req.stops == []
        assert req.pinned == []
        assert req.time_limit_sec == 60
        assert req.objective == "MAX_VOLUME_UTIL"
        assert req.seed is None

    def test_vehicle_data_validation_missing_required(self):
        """Thiếu trường bắt buộc trong VehicleData -> raise ValidationError"""
        with pytest.raises(ValidationError):
            VehicleData(inner_l=5.0)


class TestEngineOptimizationResponseDto:
    def test_engine_response_valid_construction_and_json_roundtrip(self):
        """Tạo EngineOptimizationResponse / OptimizationResult và kiểm tra JSON roundtrip"""
        pkg_placed = uuid.uuid4()
        pkg_unplaced = uuid.uuid4()

        response = EngineOptimizationResponse(
            placements=[
                PlacementData(
                    package_id=pkg_placed,
                    x=0.0,
                    y=0.0,
                    z=0.0,
                    packed_l=1.0,
                    packed_w=0.8,
                    packed_h=0.6,
                    rotation_type="0",
                    step_sequence=1,
                )
            ],
            unplaced=[
                UnplacedData(package_id=pkg_unplaced, reason="VOLUME_EXCEEDED")
            ],
            metrics=MetricsData(
                volume_utilization=0.85,
                weight_utilization=0.72,
                packed_count=1,
                computation_ms=450,
            ),
        )

        json_str = response.model_dump_json()
        restored = EngineOptimizationResponse.model_validate_json(json_str)

        assert len(restored.placements) == 1
        assert restored.placements[0].package_id == pkg_placed
        assert restored.placements[0].step_sequence == 1
        assert len(restored.unplaced) == 1
        assert restored.unplaced[0].reason == "VOLUME_EXCEEDED"
        assert restored.metrics.volume_utilization == 0.85
        assert restored.metrics.computation_ms == 450

    def test_optimization_result_alias(self):
        """OptimizationResult là alias của EngineOptimizationResponse"""
        assert OptimizationResult is EngineOptimizationResponse


class TestJobStatusNotificationDto:
    def test_job_status_notification_valid(self):
        """JobStatusNotification serialization / deserialization"""
        notif = JobStatusNotification(
            job_uuid="uuid-1234",
            status=OptimizationJobStatus.COMPLETED,
            plan_id=10,
            message="Optimization completed (5 packages placed)",
        )

        assert notif.job_uuid == "uuid-1234"
        assert notif.status == OptimizationJobStatus.COMPLETED
        assert notif.plan_id == 10

        data = notif.model_dump()
        assert data["job_uuid"] == "uuid-1234"
        assert data["status"] == "COMPLETED"
        assert data["plan_id"] == 10

    def test_job_status_notification_optional_fields(self):
        """JobStatusNotification với plan_id và message là None khi FAILED"""
        notif = JobStatusNotification(
            job_uuid="uuid-5678",
            status=OptimizationJobStatus.FAILED,
        )
        assert notif.plan_id is None
        assert notif.message is None
