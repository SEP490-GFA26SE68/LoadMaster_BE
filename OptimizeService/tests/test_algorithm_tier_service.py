import pytest
from unittest.mock import AsyncMock, MagicMock, Mock
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

        client = LoadMasterCreditClient(http_client=mock_http,token_provider=Mock(get_service_token=AsyncMock(return_value="unit-service-token")))
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

        client = LoadMasterCreditClient(http_client=mock_http,token_provider=Mock(get_service_token=AsyncMock(return_value="unit-service-token")))
        with pytest.raises(AppException) as exc_info:
            await client.deduct_credit(company_id="comp-123", reference="job-abc")
        assert exc_info.value.error_code == ErrorCode.INSUFFICIENT_CREDITS

    async def test_refund_credit_success(self):
        mock_http = AsyncMock()
        mock_response = MagicMock()
        mock_response.status_code = 200
        mock_response.json.return_value = {"success": True}
        mock_http.post.return_value = mock_response

        client = LoadMasterCreditClient(http_client=mock_http,token_provider=Mock(get_service_token=AsyncMock(return_value="unit-service-token")))
        result = await client.refund_credit(company_id="comp-123", reference="job-abc")
        assert result is True
        mock_http.post.assert_called_once()
        args, kwargs = mock_http.post.call_args
        assert "/api/credits/refund" in args[0]


from sqlalchemy.orm import Session
from sqlalchemy import text
import uuid
from tests.optimization_fixtures import reserve_job
from tests.test_load_balance_search import package,problem


@pytest.mark.anyio
class TestAsyncRunnerCreditIntegration:
    @pytest.fixture
    def test_db(self):
        engine=create_engine("sqlite:///:memory:")
        Base.metadata.create_all(engine)
        with Session(engine) as db:
            db.add(Trip(id=1,company_id=1))
            db.commit()
            yield db
        engine.dispose()

    async def test_prebilled_job_is_not_deducted_again(self,test_db):
        """Contract update: Spring has already billed; Python must NOT deduct again."""
        job=reserve_job(test_db,1,subscription_tier="PRO")
        credit=AsyncMock();solver=AsyncMock()
        p=package("1")
        solver.solve.return_value=EngineOptimizationResponse(placements=[PlacementData(
            package_id="1",x=290,y=100,z=0,packed_l=40,packed_w=30,packed_h=25)])
        runner=AsyncOptimizationRunner(optimization_client=solver,credit_client=credit,persistence_service=Mock())
        await runner.run(job.job_uuid,problem=problem([p]),db=test_db)
        credit.deduct_credit.assert_not_awaited()
        credit.refund_credit.assert_not_awaited()
        test_db.refresh(job)
        assert job.algorithm_tier=="PRO" and job.algorithm_name=="EP_DBLF_GA"
        assert job.status==OptimizationJobStatus.COMPLETED.value
        assert test_db.execute(text("SELECT count(*) FROM credit_transactions WHERE reference=:ref"),{'ref':job.job_uuid}).scalar()==1

    async def test_unbilled_job_blocks_engine_without_deduct_or_refund(self,test_db):
        """Without Spring's USAGE reservation, Python rejects before execution."""
        job=reserve_job(test_db,1)
        job.job_uuid=str(uuid.uuid4())  # no billing reference for this new UUID
        test_db.commit()
        solver=AsyncMock();credit=AsyncMock()
        runner=AsyncOptimizationRunner(optimization_client=solver,credit_client=credit)
        await runner.run(job.job_uuid,problem=problem([package("1")]),db=test_db)
        solver.solve.assert_not_awaited();credit.deduct_credit.assert_not_awaited()
        credit.refund_credit.assert_not_awaited()
        test_db.refresh(job)
        assert job.status==OptimizationJobStatus.FAILED.value

    async def test_refund_called_when_job_fails(self,test_db):
        job=reserve_job(test_db,1)
        solver=AsyncMock();solver.solve.side_effect=RuntimeError("Test engine failure")
        credit=AsyncMock()
        runner=AsyncOptimizationRunner(optimization_client=solver,credit_client=credit)
        await runner.run(job.job_uuid,problem=problem([package("1")]),db=test_db)
        credit.refund_credit.assert_awaited_once_with("1",job.job_uuid)
        credit.deduct_credit.assert_not_awaited()
        test_db.refresh(job)
        assert job.status==OptimizationJobStatus.FAILED.value
