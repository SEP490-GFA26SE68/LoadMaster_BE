import pytest
from unittest.mock import AsyncMock, MagicMock
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from app.config.database import Base
from app.constant.optimization.job_status import OptimizationJobStatus
from app.dto.request.optimization_request import OptimizationJobRequest
from app.dto.optimization.engine.problem_request import ProblemRequest, VehicleData, PackageData
from app.dto.optimization.engine.engine_response import EngineOptimizationResponse, MetricsData, PlacementData
from app.entity.optimization.optimization_job import OptimizationJob
from app.entity.trip_model import Trip, Vehicle, VehicleType, DeliveryStop, TransportOrder, CargoPackage
from app.exception.app_exception import AppException
from app.exception.error_code import ErrorCode
from app.service.optimize.algorithm_tier_service import AlgorithmTierService
from app.client.loadmaster_credit_client import LoadMasterCreditClient
from app.service.optimize.async_optimization_runner import AsyncOptimizationRunner
from app.service.optimize.optimization_job_service import OptimizationJobService


class TestAlgorithmTierService:
    def test_tier_mapping(self):
        """
        AC:
        BASIC -> EP + DBLF
        PRO -> EP + DBLF + GA
        ULTIMATE -> EP + DBLF + GA + AI
        """
        assert AlgorithmTierService.resolve_algorithm("BASIC") == "EP_DBLF"
        assert AlgorithmTierService.resolve_algorithm("PRO") == "EP_DBLF_GA"
        assert AlgorithmTierService.resolve_algorithm("ULTIMATE") == "EP_DBLF_GA_AI"

        # Case insensitive & fallback
        assert AlgorithmTierService.resolve_algorithm("basic") == "EP_DBLF"
        assert AlgorithmTierService.resolve_algorithm("pro") == "EP_DBLF_GA"
        assert AlgorithmTierService.resolve_algorithm("ultimate") == "EP_DBLF_GA_AI"
        assert AlgorithmTierService.resolve_algorithm("UNKNOWN") == "EP_DBLF"

    def test_algorithm_tier_validation(self):
        """BASIC cannot use PRO or ULTIMATE algorithms."""
        assert AlgorithmTierService.is_algorithm_allowed("BASIC", "EP_DBLF") is True
        assert AlgorithmTierService.is_algorithm_allowed("BASIC", "EP_DBLF_GA") is False
        assert AlgorithmTierService.is_algorithm_allowed("PRO", "EP_DBLF_GA") is True
        assert AlgorithmTierService.is_algorithm_allowed("PRO", "EP_DBLF_GA_AI") is False
        assert AlgorithmTierService.is_algorithm_allowed("ULTIMATE", "EP_DBLF_GA_AI") is True


@pytest.mark.anyio
class TestLoadMasterCreditClient:
    async def test_deduct_credit_success(self):
        mock_http = AsyncMock()
        mock_response = MagicMock()
        mock_response.status_code = 200
        mock_response.json.return_value = {"success": True}
        mock_http.post.return_value = mock_response

        client = LoadMasterCreditClient(http_client=mock_http)
        result = await client.deduct_credit(company_id="comp-123", reference="job-abc")
        assert result is True
        mock_http.post.assert_called_once()
        args, kwargs = mock_http.post.call_args
        assert "/api/credits/deduct" in args[0]
        assert kwargs["json"] == {"company_id": "comp-123", "reference": "job-abc"}

    async def test_deduct_credit_insufficient_raises_error(self):
        mock_http = AsyncMock()
        mock_response = MagicMock()
        mock_response.status_code = 402
        mock_response.json.return_value = {"success": False, "message": "INSUFFICIENT_CREDITS"}
        mock_http.post.return_value = mock_response

        client = LoadMasterCreditClient(http_client=mock_http)
        with pytest.raises(AppException) as exc_info:
            await client.deduct_credit(company_id="comp-123", reference="job-abc")
        assert exc_info.value.error_code == ErrorCode.INSUFFICIENT_CREDITS

    async def test_refund_credit_success(self):
        mock_http = AsyncMock()
        mock_response = MagicMock()
        mock_response.status_code = 200
        mock_response.json.return_value = {"success": True}
        mock_http.post.return_value = mock_response

        client = LoadMasterCreditClient(http_client=mock_http)
        result = await client.refund_credit(company_id="comp-123", reference="job-abc")
        assert result is True
        mock_http.post.assert_called_once()
        args, kwargs = mock_http.post.call_args
        assert "/api/credits/refund" in args[0]


@pytest.mark.anyio
class TestAsyncRunnerCreditIntegration:
    @pytest.fixture
    def test_db(self):
        engine = create_engine("sqlite:///:memory:")
        Base.metadata.create_all(engine)
        session_factory = sessionmaker(bind=engine)
        session = session_factory()

        # Seed minimal data
        vt = VehicleType(id=1, name="Standard Truck", inner_length=6000, inner_width=2000, inner_height=2000, max_payload_kg=5000)
        v = Vehicle(id=1, vehicle_type_id=1, license_plate="29C-12345")
        t = Trip(id="trip-100", vehicle_id=1)
        stop = DeliveryStop(id=1, trip_id="trip-100", stop_sequence=1)
        order = TransportOrder(id=1, delivery_stop_id=1)
        pkg = CargoPackage(id=1, order_id=1, actual_weight_kg=100.0)

        session.add_all([vt, v, t, stop, order, pkg])
        session.commit()

        yield session
        session.close()

    async def test_credit_deducted_before_solving(self, test_db):
        job_service = OptimizationJobService()
        req = OptimizationJobRequest(
            trip_id="trip-100",
            company_id="comp-1",
            subscription_tier="PRO",
        )
        job = job_service.create_job("trip-100", req, test_db)

        mock_credit_client = AsyncMock()
        mock_credit_client.deduct_credit.return_value = True

        mock_opt_client = AsyncMock()
        mock_opt_client.solve.return_value = EngineOptimizationResponse(
            placements=[PlacementData(package_id="1", x=0, y=0, z=0, packed_l=1, packed_w=1, packed_h=1)],
            unplaced=[],
            metrics=MetricsData(volume_utilization=0.5, weight_utilization=0.5, computation_ms=10),
        )

        runner = AsyncOptimizationRunner(
            job_service=job_service,
            optimization_client=mock_opt_client,
            session_factory=lambda: test_db,
        )
        runner.credit_client = mock_credit_client

        await runner.run(job.job_uuid, db=test_db)

        # 1. Credit deduct must be called
        mock_credit_client.deduct_credit.assert_called_once_with("comp-1", job.job_uuid)
        # 2. Algorithm tier must be stored
        updated_job = job_service.get_job(job.job_uuid, test_db)
        assert updated_job.algorithm_tier == "PRO"
        assert updated_job.algorithm_name == "EP_DBLF_GA"
        assert updated_job.status == OptimizationJobStatus.COMPLETED.value

    async def test_insufficient_credit_blocks_engine_and_fails_job(self, test_db):
        job_service = OptimizationJobService()
        req = OptimizationJobRequest(
            trip_id="trip-100",
            company_id="comp-empty",
            subscription_tier="BASIC",
        )
        job = job_service.create_job("trip-100", req, test_db)

        mock_credit_client = AsyncMock()
        mock_credit_client.deduct_credit.side_effect = AppException(ErrorCode.INSUFFICIENT_CREDITS)

        mock_opt_client = AsyncMock()

        runner = AsyncOptimizationRunner(
            job_service=job_service,
            optimization_client=mock_opt_client,
            session_factory=lambda: test_db,
        )
        runner.credit_client = mock_credit_client

        await runner.run(job.job_uuid, db=test_db)

        # Engine must NOT be called
        mock_opt_client.solve.assert_not_called()
        # Job must be FAILED
        updated_job = job_service.get_job(job.job_uuid, test_db)
        assert updated_job.status == OptimizationJobStatus.FAILED.value

    async def test_refund_called_when_job_fails(self, test_db):
        job_service = OptimizationJobService()
        req = OptimizationJobRequest(
            trip_id="trip-100",
            company_id="comp-1",
            subscription_tier="BASIC",
        )
        job = job_service.create_job("trip-100", req, test_db)

        mock_credit_client = AsyncMock()
        mock_credit_client.deduct_credit.return_value = True
        mock_credit_client.refund_credit.return_value = True

        mock_opt_client = AsyncMock()
        mock_opt_client.solve.side_effect = Exception("Optimization engine crashed")

        runner = AsyncOptimizationRunner(
            job_service=job_service,
            optimization_client=mock_opt_client,
            session_factory=lambda: test_db,
        )
        runner.credit_client = mock_credit_client

        await runner.run(job.job_uuid, db=test_db)

        # Refund must be called because job failed
        mock_credit_client.refund_credit.assert_called_once_with("comp-1", job.job_uuid)
        updated_job = job_service.get_job(job.job_uuid, test_db)
        assert updated_job.status == OptimizationJobStatus.FAILED.value
