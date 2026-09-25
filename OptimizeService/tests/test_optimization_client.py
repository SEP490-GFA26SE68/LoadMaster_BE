"""
Tests for S3-06 · OptimizationClient

Seams under test:
  - OptimizationClient.solve(problem: ProblemRequest) -> EngineOptimizationResponse
  - OptimizationClient.solve_in_process(problem: ProblemRequest) -> EngineOptimizationResponse
  - External engine HTTP calls with Bearer token and timeout
  - Error propagation on timeout and HTTP error

Acceptance criteria:
  1. Interface/class: OptimizationClient.solve(problem) -> EngineOptimizationResponse
  2. In-process solver (thuật toán nội bộ)
  3. External engine: dùng httpx.AsyncClient + Bearer token từ KeycloakTokenProvider
  4. URL engine cấu hình qua settings: OPTIMIZATION_ENGINE_URL
  5. Timeout cấu hình: OPTIMIZATION_ENGINE_TIMEOUT_SEC
"""
import uuid
import pytest
import httpx

from app.config.settings import settings
from app.dto.optimization.engine.problem_request import ProblemRequest, VehicleData, PackageData
from app.dto.optimization.engine.engine_response import EngineOptimizationResponse
from app.service.optimize.optimization_client import OptimizationClient


@pytest.fixture
def sample_problem():
    pkg_id = uuid.uuid4()
    return ProblemRequest(
        vehicle=VehicleData(
            inner_l=6000.0,
            inner_w=2400.0,
            inner_h=2400.0,
            max_payload_kg=5000.0,
        ),
        packages=[
            PackageData(
                id=pkg_id,
                l=1000.0,
                w=800.0,
                h=600.0,
                weight=50.0,
                allowed_rotations=[0],
            )
        ],
        objective="MAX_VOLUME_UTIL",
        time_limit_sec=60,
    )


class MockTokenProvider:
    async def get_service_token(self) -> str:
        return "mock-service-token-123"


class TestOptimizationClientHttp:
    @pytest.mark.asyncio
    async def test_solve_http_success_sends_bearer_and_returns_engine_response(self, sample_problem):
        """Gọi HTTP engine thành công: gửi Bearer token và parse EngineOptimizationResponse"""
        pkg_id = sample_problem.packages[0].id

        def mock_handler(request: httpx.Request) -> httpx.Response:
            assert request.method == "POST"
            assert request.url.path == "/api/v1/optimization/jobs"
            assert request.headers["Authorization"] == "Bearer mock-service-token-123"
            return httpx.Response(
                200,
                json={
                    "placements": [
                        {
                            "package_id": str(pkg_id),
                            "x": 0.0,
                            "y": 0.0,
                            "z": 0.0,
                            "packed_l": 1000.0,
                            "packed_w": 800.0,
                            "packed_h": 600.0,
                            "rotation_type": 0,
                            "step_sequence": 1,
                        }
                    ],
                    "unplaced": [],
                    "metrics": {
                        "volume_utilization": 0.014,
                        "weight_utilization": 0.01,
                        "packed_count": 1,
                        "computation_ms": 120,
                    },
                },
            )

        client_transport = httpx.MockTransport(mock_handler)
        async with httpx.AsyncClient(transport=client_transport) as http_client:
            opt_client = OptimizationClient(
                token_provider=MockTokenProvider(),
                engine_url="http://engine.internal:8001",
                timeout_sec=30,
                http_client=http_client,
            )

            result = await opt_client.solve(sample_problem)

            assert isinstance(result, EngineOptimizationResponse)
            assert len(result.placements) == 1
            assert str(result.placements[0].package_id) == str(pkg_id)
            assert result.metrics.packed_count == 1
            assert result.metrics.computation_ms == 120

    @pytest.mark.asyncio
    async def test_solve_http_timeout_raises_timeout_exception(self, sample_problem):
        """Khi engine timeout, ném httpx.TimeoutException"""
        def mock_handler(request: httpx.Request) -> httpx.Response:
            raise httpx.TimeoutException("Read timed out")

        client_transport = httpx.MockTransport(mock_handler)
        async with httpx.AsyncClient(transport=client_transport) as http_client:
            opt_client = OptimizationClient(
                token_provider=MockTokenProvider(),
                engine_url="http://engine.internal:8001",
                timeout_sec=5,
                http_client=http_client,
            )

            with pytest.raises(httpx.TimeoutException):
                await opt_client.solve(sample_problem)

    @pytest.mark.asyncio
    async def test_solve_http_server_error_raises_status_error(self, sample_problem):
        """Khi engine trả về 500, ném httpx.HTTPStatusError"""
        def mock_handler(request: httpx.Request) -> httpx.Response:
            return httpx.Response(500, json={"error": "Internal engine failure"})

        client_transport = httpx.MockTransport(mock_handler)
        async with httpx.AsyncClient(transport=client_transport) as http_client:
            opt_client = OptimizationClient(
                token_provider=MockTokenProvider(),
                engine_url="http://engine.internal:8001",
                http_client=http_client,
            )

            with pytest.raises(httpx.HTTPStatusError):
                await opt_client.solve(sample_problem)


class TestOptimizationClientInProcess:
    def test_solve_in_process_places_fitting_packages_and_computes_metrics(self, sample_problem):
        """solve_in_process xếp các kiện hàng fit xe và tính toán các metrics"""
        opt_client = OptimizationClient()

        result = opt_client.solve_in_process(sample_problem)

        assert isinstance(result, EngineOptimizationResponse)
        assert len(result.placements) == 1
        assert len(result.unplaced) == 0
        assert result.metrics.packed_count == 1
        assert result.metrics.volume_utilization > 0.0
        assert result.metrics.weight_utilization > 0.0
        assert result.metrics.computation_ms >= 0

    def test_solve_in_process_marks_oversized_packages_unplaced(self):
        """Kiện hàng vượt kích thước xe được đưa vào danh sách unplaced"""
        problem = ProblemRequest(
            vehicle=VehicleData(inner_l=1.0, inner_w=1.0, inner_h=1.0, max_payload_kg=100.0),
            packages=[
                PackageData(
                    id=uuid.uuid4(),
                    l=2.0,  # vượt chiều dài
                    w=2.0,
                    h=2.0,
                    weight=10.0,
                )
            ],
        )
        opt_client = OptimizationClient()

        result = opt_client.solve_in_process(problem)

        assert len(result.placements) == 0
        assert len(result.unplaced) == 1
        assert "DOES_NOT_FIT" in result.unplaced[0].reason or "VOLUME" in result.unplaced[0].reason
