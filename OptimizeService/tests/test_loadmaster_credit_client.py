"""
Unit tests for LoadmasterCreditClient — S8-08
"""
import pytest
import httpx
from unittest.mock import AsyncMock, MagicMock, patch

from app.client.loadmaster_credit_client import LoadmasterCreditClient


class MockTokenProvider:
    async def get_service_token(self) -> str:
        return "mock-service-token-xyz"


@pytest.fixture
def mock_token_provider():
    return MockTokenProvider()


class TestLoadmasterCreditClient:

    @pytest.mark.asyncio
    async def test_refund_credit_success(self, mock_token_provider):
        """Test refund credit call successfully on first try"""
        handler = MagicMock()

        def app(request: httpx.Request):
            assert request.headers["Authorization"] == "Bearer mock-service-token-xyz"
            assert request.url.path == "/api/internal/credits/refund"
            return httpx.Response(200, json={"success": True, "message": "Credit refunded successfully"})

        transport = httpx.MockTransport(app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test-server") as http_client:
            client = LoadmasterCreditClient(
                base_url="http://test-server",
                token_provider=mock_token_provider,
                http_client=http_client,
                max_retries=3,
            )
            success = await client.refund_credit_async("job-uuid-123")
            assert success is True

    @pytest.mark.asyncio
    async def test_refund_credit_retry_success(self, mock_token_provider):
        """Test refund credit retries on 503 and succeeds on 2nd try"""
        attempt = 0

        def app(request: httpx.Request):
            nonlocal attempt
            attempt += 1
            if attempt == 1:
                return httpx.Response(503, json={"error": "Service Unavailable"})
            return httpx.Response(200, json={"success": True})

        transport = httpx.MockTransport(app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test-server") as http_client:
            client = LoadmasterCreditClient(
                base_url="http://test-server",
                token_provider=mock_token_provider,
                http_client=http_client,
                max_retries=3,
                retry_delay=0.01,
            )
            success = await client.refund_credit_async("job-uuid-123")
            assert success is True
            assert attempt == 2

    @pytest.mark.asyncio
    async def test_refund_credit_all_retries_fail_logs_manual_review(self, mock_token_provider, caplog):
        """Test refund credit retries 3 times, all fail, logs manual review"""
        def app(request: httpx.Request):
            return httpx.Response(500, json={"error": "Internal Server Error"})

        transport = httpx.MockTransport(app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test-server") as http_client:
            client = LoadmasterCreditClient(
                base_url="http://test-server",
                token_provider=mock_token_provider,
                http_client=http_client,
                max_retries=3,
                retry_delay=0.01,
            )
            success = await client.refund_credit_async("job-uuid-123")
            assert success is False
            assert "manual review" in caplog.text.lower()
