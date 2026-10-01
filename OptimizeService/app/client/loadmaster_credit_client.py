from __future__ import annotations

import logging
from typing import Optional, Union, Any
import httpx

from app.config.settings import settings
from app.exception.app_exception import AppException
from app.exception.error_code import ErrorCode
from app.service.auth.keycloak_token_provider import KeycloakTokenProvider

logger = logging.getLogger(__name__)


class LoadMasterCreditClient:
    """
    Client giao tiếp với LoadMasterService (Spring Boot) để trừ và hoàn credit cho Company (S5b-05).
    """

    def __init__(
        self,
        base_url: Optional[str] = None,
        token_provider: Optional[Any] = None,
        http_client: Optional[httpx.AsyncClient] = None,
        timeout_sec: int = 10,
    ):
        self.base_url = (base_url or settings.LOADMASTER_SERVICE_URL).rstrip("/")
        self.token_provider = token_provider or KeycloakTokenProvider()
        self.http_client = http_client
        self.timeout_sec = timeout_sec

    async def _get_headers(self) -> dict[str, str]:
        headers = {"Content-Type": "application/json"}
        if self.token_provider:
            try:
                token = await self.token_provider.get_service_token()
                if token:
                    headers["Authorization"] = f"Bearer {token}"
            except Exception as e:
                logger.warning(f"Could not obtain service token for LoadMaster credit client: {e}")
        return headers

    async def deduct_credit(self, company_id: Union[str, int], reference: str) -> bool:
        """
        Gọi POST /api/credits/deduct để trừ 1 credit của company.
        Ném AppException(ErrorCode.INSUFFICIENT_CREDITS) nếu công ty không đủ credit (HTTP 402 hoặc code INSUFFICIENT_CREDITS).
        """
        url = f"{self.base_url}/api/credits/deduct"
        payload = {"company_id": str(company_id), "reference": str(reference)}
        headers = await self._get_headers()

        if self.http_client:
            resp = await self.http_client.post(url, json=payload, headers=headers)
        else:
            async with httpx.AsyncClient(timeout=float(self.timeout_sec)) as client:
                resp = await client.post(url, json=payload, headers=headers)

        if resp.status_code == 402:
            raise AppException(ErrorCode.INSUFFICIENT_CREDITS)

        if resp.status_code >= 400:
            try:
                data = resp.json()
                if data.get("message") == "INSUFFICIENT_CREDITS" or data.get("code") == "INSUFFICIENT_CREDITS":
                    raise AppException(ErrorCode.INSUFFICIENT_CREDITS)
            except (ValueError, KeyError):
                pass
            resp.raise_for_status()

        return True

    async def refund_credit(self, company_id: Union[str, int], reference: str) -> bool:
        """
        Gọi POST /api/credits/refund để hoàn trả 1 credit khi job bị FAILED.
        """
        url = f"{self.base_url}/api/credits/refund"
        payload = {"company_id": str(company_id), "reference": str(reference)}
        headers = await self._get_headers()

        try:
            if self.http_client:
                resp = await self.http_client.post(url, json=payload, headers=headers)
            else:
                async with httpx.AsyncClient(timeout=float(self.timeout_sec)) as client:
                    resp = await client.post(url, json=payload, headers=headers)

            if resp.status_code in (200, 201, 204):
                return True
            logger.warning(f"Refund credit returned non-success status: {resp.status_code}")
            return False
        except Exception as e:
            logger.error(f"Error calling refund credit for company {company_id}, ref {reference}: {e}")
            return False
