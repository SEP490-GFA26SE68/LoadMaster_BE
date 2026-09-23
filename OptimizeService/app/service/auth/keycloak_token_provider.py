from __future__ import annotations

import time
from typing import Optional
import httpx

from app.config.settings import settings
from app.exception.app_exception import ServiceAuthenticationException


class KeycloakTokenProvider:
    """
    Class lấy service access token từ Keycloak bằng client_credentials grant.
    Tự động cache token và refresh khi thời gian hiệu lực còn < 30s (S3-06b).
    """

    def __init__(
        self,
        auth_url: Optional[str] = None,
        realm: Optional[str] = None,
        client_id: Optional[str] = None,
        client_secret: Optional[str] = None,
        http_client: Optional[httpx.AsyncClient] = None,
    ):
        self.auth_url = auth_url or settings.KEYCLOAK_AUTH_URL
        self.realm = realm or settings.KEYCLOAK_REALM
        self.client_id = client_id or settings.KEYCLOAK_CLIENT_ID
        self.client_secret = client_secret or settings.KEYCLOAK_CLIENT_SECRET
        self.http_client = http_client
        self._cached_token: Optional[str] = None
        self._expires_at: float = 0.0

    async def get_service_token(self) -> str:
        """
        Lấy access_token hợp lệ.
        Nếu token đã cache còn hạn > 30s thì tái sử dụng cache.
        Ngược lại gửi request client_credentials tới Keycloak để lấy token mới.
        """
        now = time.time()
        # Cache check: còn hạn và chưa rơi vào ngưỡng 30s trước khi hết hạn
        if self._cached_token and now < (self._expires_at - 30):
            return self._cached_token

        token_url = f"{self.auth_url.rstrip('/')}/realms/{self.realm}/protocol/openid-connect/token"
        payload = {
            "grant_type": "client_credentials",
            "client_id": self.client_id,
            "client_secret": self.client_secret,
        }
        headers = {"Content-Type": "application/x-www-form-urlencoded"}

        try:
            if self.http_client is not None:
                resp = await self.http_client.post(token_url, data=payload, headers=headers)
                if resp.status_code != 200:
                    raise ServiceAuthenticationException(
                        f"Keycloak xác thực thất bại (HTTP {resp.status_code}): {resp.text}"
                    )
                data = resp.json()
            else:
                async with httpx.AsyncClient(timeout=10.0) as client:
                    resp = await client.post(token_url, data=payload, headers=headers)
                    if resp.status_code != 200:
                        raise ServiceAuthenticationException(
                            f"Keycloak xác thực thất bại (HTTP {resp.status_code}): {resp.text}"
                        )
                    data = resp.json()

            token = data.get("access_token")
            expires_in = data.get("expires_in", 300)
            if not token:
                raise ServiceAuthenticationException("Phản hồi từ Keycloak thiếu trường access_token")

            self._cached_token = token
            self._expires_at = time.time() + float(expires_in)
            return token

        except ServiceAuthenticationException:
            raise
        except Exception as e:
            raise ServiceAuthenticationException(f"Không thể kết nối tới Keycloak: {str(e)}") from e
