"""
Tests for S3-06b · Keycloak Service Token Provider

Seams under test:
  - KeycloakTokenProvider.get_service_token() -> str
  - Token caching & auto-refresh (< 30s threshold)
  - Error handling: raises ServiceAuthenticationException on failure

Acceptance Criteria:
  1. POST {KEYCLOAK_AUTH_URL}/realms/{KEYCLOAK_REALM}/protocol/openid-connect/token
  2. Body: grant_type=client_credentials, client_id, client_secret
  3. Parse access_token + expires_in
  4. Cache token (không gọi lại Keycloak khi còn hạn)
  5. Auto-refresh khi còn < 30s
  6. Method public get_service_token() -> str
  7. Handle error: Keycloak không khả dụng -> raise ServiceAuthenticationException
"""
import time
import pytest
import httpx

from app.exception.app_exception import ServiceAuthenticationException
from app.service.auth.keycloak_token_provider import KeycloakTokenProvider


class TestKeycloakTokenProvider:
    @pytest.mark.asyncio
    async def test_get_service_token_success(self):
        """Lấy token thành công từ Keycloak với client_credentials grant"""
        call_count = 0

        def mock_handler(request: httpx.Request) -> httpx.Response:
            nonlocal call_count
            call_count += 1
            assert request.method == "POST"
            assert request.url.path == "/realms/loadmaster/protocol/openid-connect/token"

            # Parse urlencoded body
            body_text = request.read().decode("utf-8")
            assert "grant_type=client_credentials" in body_text
            assert "client_id=test-client" in body_text
            assert "client_secret=test-secret" in body_text

            return httpx.Response(
                200,
                json={
                    "access_token": "valid-token-xyz",
                    "expires_in": 300,
                    "token_type": "Bearer",
                },
            )

        transport = httpx.MockTransport(mock_handler)
        async with httpx.AsyncClient(transport=transport) as client:
            provider = KeycloakTokenProvider(
                auth_url="http://auth.local:8080",
                realm="loadmaster",
                client_id="test-client",
                client_secret="test-secret",
                http_client=client,
            )

            token = await provider.get_service_token()
            assert token == "valid-token-xyz"
            assert call_count == 1

    @pytest.mark.asyncio
    async def test_get_service_token_uses_cache(self):
        """Gọi lại nhiều lần khi token còn hạn không gửi thêm HTTP request"""
        call_count = 0

        def mock_handler(request: httpx.Request) -> httpx.Response:
            nonlocal call_count
            call_count += 1
            return httpx.Response(
                200,
                json={"access_token": "cached-token-1", "expires_in": 300},
            )

        transport = httpx.MockTransport(mock_handler)
        async with httpx.AsyncClient(transport=transport) as client:
            provider = KeycloakTokenProvider(
                auth_url="http://auth.local:8080",
                realm="loadmaster",
                client_id="test-client",
                client_secret="test-secret",
                http_client=client,
            )

            token1 = await provider.get_service_token()
            token2 = await provider.get_service_token()
            token3 = await provider.get_service_token()

            assert token1 == "cached-token-1"
            assert token2 == "cached-token-1"
            assert token3 == "cached-token-1"
            assert call_count == 1

    @pytest.mark.asyncio
    async def test_get_service_token_auto_refreshes_when_expiring_soon(self):
        """Khi thời gian còn lại < 30s, provider tự động fetch token mới"""
        call_count = 0

        def mock_handler(request: httpx.Request) -> httpx.Response:
            nonlocal call_count
            call_count += 1
            return httpx.Response(
                200,
                json={"access_token": f"token-{call_count}", "expires_in": 300},
            )

        transport = httpx.MockTransport(mock_handler)
        async with httpx.AsyncClient(transport=transport) as client:
            provider = KeycloakTokenProvider(
                auth_url="http://auth.local:8080",
                realm="loadmaster",
                client_id="test-client",
                client_secret="test-secret",
                http_client=client,
            )

            token1 = await provider.get_service_token()
            assert token1 == "token-1"
            assert call_count == 1

            # Giả lập token sắp hết hạn: còn lại 20 giây (< 30s ngưỡng auto-refresh)
            provider._expires_at = time.time() + 20

            token2 = await provider.get_service_token()
            assert token2 == "token-2"
            assert call_count == 2

    @pytest.mark.asyncio
    async def test_get_service_token_http_error_raises_service_authentication_exception(self):
        """Keycloak trả về HTTP 401 hoặc 500 -> raise ServiceAuthenticationException"""
        def mock_handler(request: httpx.Request) -> httpx.Response:
            return httpx.Response(401, json={"error": "invalid_client"})

        transport = httpx.MockTransport(mock_handler)
        async with httpx.AsyncClient(transport=transport) as client:
            provider = KeycloakTokenProvider(
                auth_url="http://auth.local:8080",
                realm="loadmaster",
                client_id="test-client",
                client_secret="bad-secret",
                http_client=client,
            )

            with pytest.raises(ServiceAuthenticationException):
                await provider.get_service_token()

    @pytest.mark.asyncio
    async def test_get_service_token_network_error_raises_service_authentication_exception(self):
        """Lỗi mạng hoặc timeout tới Keycloak -> raise ServiceAuthenticationException"""
        def mock_handler(request: httpx.Request) -> httpx.Response:
            raise httpx.ConnectError("Connection refused")

        transport = httpx.MockTransport(mock_handler)
        async with httpx.AsyncClient(transport=transport) as client:
            provider = KeycloakTokenProvider(
                auth_url="http://auth.local:8080",
                realm="loadmaster",
                client_id="test-client",
                client_secret="test-secret",
                http_client=client,
            )

            with pytest.raises(ServiceAuthenticationException):
                await provider.get_service_token()
