from __future__ import annotations

import asyncio
import logging
from typing import Optional, Union, Any
import httpx

from app.config.settings import settings
from app.exception.app_exception import AppException
from app.exception.error_code import ErrorCode

logger = logging.getLogger(__name__)


class LoadMasterCreditClient:
    """
    Client giao tiếp với LoadMasterService để:
    1. Trừ credit trước khi giải bài toán tối ưu (S5b-05).
    2. Hoàn trả credit khi job FAILED (S5b-05 & S8-08).
       - Có cơ chế retry tối đa 3 lần nếu LoadMasterService không khả dụng (5xx, Connection error).
       - Tự động lấy service token từ KeycloakTokenProvider.
       - Ghi log cảnh báo manual review nếu thất bại sau các lần retry.
    """

    def __init__(
        self,
        base_url: Optional[str] = None,
        token_provider: Optional[Any] = None,
        http_client: Optional[httpx.AsyncClient] = None,
        timeout_sec: int = 10,
        max_retries: int = 3,
        retry_delay: float = 1.0,
    ):
        raw_url = base_url or getattr(settings, "LOADMASTER_SERVICE_URL", "http://localhost:8080")
        self.base_url = raw_url.rstrip("/")
        self.http_client = http_client
        self.timeout_sec = timeout_sec
        self.max_retries = max_retries
        self.retry_delay = retry_delay

        if token_provider is not None:
            self.token_provider = token_provider
        else:
            try:
                from app.service.auth.keycloak_token_provider import KeycloakTokenProvider
                self.token_provider = KeycloakTokenProvider()
            except ImportError:
                self.token_provider = None

    async def _get_headers(self) -> dict[str, str]:
        headers = {"Content-Type": "application/json"}
        if self.token_provider:
            try:
                if hasattr(self.token_provider, "get_service_token"):
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

    async def refund_credit_async(
        self,
        job_uuid: str,
        company_id: Optional[Union[str, int]] = None,
    ) -> bool:
        """
        Gọi POST /api/internal/credits/refund với retry tối đa max_retries lần (S8-08).
        """
        url = f"{self.base_url}/api/internal/credits/refund"
        payload = {"reference": str(job_uuid)}
        if company_id is not None:
            payload["companyId"] = company_id

        headers = await self._get_headers()

        for attempt in range(1, self.max_retries + 1):
            try:
                if self.http_client is not None:
                    resp = await self.http_client.post(url, json=payload, headers=headers)
                else:
                    async with httpx.AsyncClient(timeout=float(self.timeout_sec)) as client:
                        resp = await client.post(url, json=payload, headers=headers)

                if resp.status_code in (200, 201, 204):
                    logger.info(f"Successfully refunded credit for job {job_uuid}")
                    return True
                elif resp.status_code < 500 and resp.status_code != 404:
                    logger.error(f"Credit refund rejected with status {resp.status_code}: {resp.text}")
                    break
                else:
                    logger.warning(
                        f"Attempt {attempt}/{self.max_retries} to refund credit for job {job_uuid} failed with status {resp.status_code}"
                    )
            except Exception as exc:
                logger.warning(
                    f"Attempt {attempt}/{self.max_retries} to refund credit for job {job_uuid} encountered error: {exc}"
                )

            if attempt < self.max_retries:
                await asyncio.sleep(self.retry_delay)

        logger.error(
            f"Credit refund failed after {self.max_retries} attempts for job {job_uuid}. Requires manual review."
        )
        return False

    async def refund_credit(
        self,
        company_id_or_ref: Optional[Union[str, int]] = None,
        reference: Optional[str] = None,
        *,
        company_id: Optional[Union[str, int]] = None,
        job_uuid: Optional[str] = None,
    ) -> bool:
        """
        Hoàn trả credit cho job thất bại.
        Hỗ trợ cả 2 signature:
        - refund_credit(company_id, reference) -> gọi POST /api/credits/refund (S5b-05)
        - refund_credit(job_uuid) -> gọi POST /api/internal/credits/refund với retry (S8-08)
        """
        actual_company_id = company_id
        actual_ref = reference or job_uuid

        if reference is not None and company_id_or_ref is not None:
            actual_company_id = company_id_or_ref
            actual_ref = reference
        elif company_id_or_ref is not None and reference is None:
            actual_ref = str(company_id_or_ref)

        if actual_company_id is not None:
            url = f"{self.base_url}/api/credits/refund"
            payload = {"company_id": str(actual_company_id), "reference": str(actual_ref)}
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
                logger.error(f"Error calling refund credit for company {actual_company_id}, ref {actual_ref}: {e}")
                return False
        else:
            return await self.refund_credit_async(str(actual_ref))

    def refund_credit_sync(self, job_uuid: str, company_id: Optional[int] = None) -> bool:
        """Synchronous wrapper for refund_credit_async."""
        try:
            loop = asyncio.get_event_loop()
            if loop.is_running():
                import concurrent.futures
                with concurrent.futures.ThreadPoolExecutor() as pool:
                    return pool.submit(asyncio.run, self.refund_credit_async(job_uuid, company_id)).result()
            else:
                return loop.run_until_complete(self.refund_credit_async(job_uuid, company_id))
        except Exception:
            return asyncio.run(self.refund_credit_async(job_uuid, company_id))


# Backward compatibility alias
LoadmasterCreditClient = LoadMasterCreditClient
