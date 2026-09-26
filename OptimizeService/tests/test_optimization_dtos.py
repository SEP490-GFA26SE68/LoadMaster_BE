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
from app.constant.optimization.objective import OptimizationObjective
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
from app.dto.request.optimization_request import OptimizationJobRequest
from app.dto.response.optimization_job_response import OptimizationJobResponse
from app.dto.response.load_plan_response import LoadPlanResponse
from app.dto.response.package_placement_response import PackagePlacementResponse
from app.dto.response.validation_response import ValidationResponse


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

    def test_job_status_notification_supports_numeric_job_id(self):
        """JobStatusNotification hỗ trợ numeric job_id theo schema v3.4"""
        notif = JobStatusNotification(
            job_id=10,
            status=OptimizationJobStatus.COMPLETED,
            plan_id=1,
            message="Completed",
        )
        assert notif.job_id == 10
        assert notif.status == OptimizationJobStatus.COMPLETED


class TestSchemaV34DtoContracts:
    def test_package_data_supports_numeric_id(self):
        """PackageData hỗ trợ numeric id (BIGSERIAL) từ database"""
        pkg = PackageData(
            id=101,
            l=1000.0,
            w=800.0,
            h=600.0,
            weight=50.0,
            stop_index=1,
        )
        assert pkg.id == 101

    def test_problem_request_with_pinned_package_ids(self):
        """ProblemRequest hỗ trợ pinned_package_ids theo S3-05 specification"""
        req = ProblemRequest(
            vehicle=VehicleData(inner_l=6.0, inner_w=2.4, inner_h=2.4, max_payload_kg=5000.0),
            packages=[
                PackageData(id=101, l=1.0, w=0.8, h=0.6, weight=50.0, stop_index=1)
            ],
            pinned_package_ids=[101, 102],
        )
        assert req.pinned_package_ids == [101, 102]

    def test_placement_data_supports_numeric_package_id_and_loading_sequence(self):
        """PlacementData hỗ trợ numeric package_id và loading_sequence theo schema v3.4"""
        p = PlacementData(
            package_id=101,
            x=0.0,
            y=0.0,
            z=0.0,
            loading_sequence=1,
        )
        assert p.package_id == 101
        assert p.loading_sequence == 1

    def test_unplaced_data_supports_numeric_package_id_and_reason_code(self):
        """UnplacedData hỗ trợ numeric package_id và reason_code"""
        u = UnplacedData(
            package_id=101,
            reason_code="EXCEED_PAYLOAD",
        )
        assert u.package_id == 101
        assert u.reason_code == "EXCEED_PAYLOAD"

    def test_optimization_job_request_validation(self):
        """OptimizationJobRequest validation: time_limit_sec range và trip_id int/str"""
        req = OptimizationJobRequest(trip_id=3501, time_limit_sec=120)
        assert req.trip_id == 3501
        assert req.time_limit_sec == 120
        assert req.objective == OptimizationObjective.MAX_VOLUME

        with pytest.raises(ValidationError):
            OptimizationJobRequest(trip_id=3501, time_limit_sec=5)  # < 10

        with pytest.raises(ValidationError):
            OptimizationJobRequest(trip_id=3501, time_limit_sec=1000)  # > 600

    def test_optimization_job_response_schema_v34_and_aliases(self):
        """OptimizationJobResponse hỗ trợ schema v3.4 và camelCase aliases"""
        resp = OptimizationJobResponse(
            id=1,
            trip_id=3501,
            algorithm_objective="MAX_VOLUME",
            execution_time_ms=500,
            status="COMPLETED",
        )
        assert resp.id == 1
        assert resp.trip_id == 3501
        assert resp.tripId == 3501
        assert resp.algorithm_objective == "MAX_VOLUME"
        assert resp.algorithmObjective == "MAX_VOLUME"
        assert resp.execution_time_ms == 500
        assert resp.executionTimeMs == 500
        assert resp.status == "COMPLETED"

    def test_load_plan_response_schema_v34(self):
        """LoadPlanResponse hỗ trợ schema v3.4 (plan_version, volume_utilization_percent, is_approved, approved_by_user_id)"""
        resp = LoadPlanResponse(
            id=1,
            job_id=10,
            plan_version=2,
            volume_utilization_percent=85.5,
            is_approved=True,
            approved_by_user_id=42,
        )
        assert resp.id == 1
        assert resp.job_id == 10
        assert resp.plan_version == 2
        assert resp.volume_utilization_percent == 85.5
        assert resp.is_approved is True
        assert resp.approved_by_user_id == 42

    def test_package_placement_response_schema_v34(self):
        """PackagePlacementResponse hỗ trợ schema v3.4 (load_plan_id, numeric package_id, loading_sequence)"""
        resp = PackagePlacementResponse(
            id=1,
            load_plan_id=10,
            package_id=101,
            pos_x=100.0,
            pos_y=200.0,
            pos_z=300.0,
            loading_sequence=2,
        )
        assert resp.id == 1
        assert resp.load_plan_id == 10
        assert resp.package_id == 101 or resp.package_id == "101"
        assert resp.loading_sequence == 2

    def test_validation_response_schema(self):
        """ValidationResponse validation"""
        resp = ValidationResponse(
            can_optimize=True,
            warnings=["Weight close to limit"],
            errors=[],
        )
        assert resp.can_optimize is True
        assert resp.warnings == ["Weight close to limit"]
        assert resp.errors == []
