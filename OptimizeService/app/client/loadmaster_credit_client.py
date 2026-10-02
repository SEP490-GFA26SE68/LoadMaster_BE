from __future__ import annotations

import asyncio
import logging
from typing import Optional, Any
import httpx

from app.config.settings import settings

logger = logging.getLogger(__name__)


class LoadmasterCreditClient:
    """
    Client gọi API nội bộ của LoadMasterService để hoàn credit khi job FAILED (S8-08).
    - Retry tối đa 3 lần nếu LoadMasterService không khả dụng (5xx, Connection error).
    - Dùng service token từ KeycloakTokenProvider.
    - Ghi log cảnh báo manual review nếu thất bại sau 3 lần retry.
    """

    def __init__(
        self,
        base_url: Optional[str] = None,
        token_provider: Optional[Any] = None,
        http_client: Optional[httpx.AsyncClient] = None,
        max_retries: int = 3,
        retry_delay: float = 1.0,
    ):
        raw_url = base_url or getattr(settings, "LOADMASTER_SERVICE_URL", "http://localhost:8080")
        self.base_url = raw_url.rstrip("/")
        self.max_retries = max_retries
        self.retry_delay = retry_delay
        self.http_client = http_client

        if token_provider is not None:
            self.token_provider = token_provider
        else:
            try:
                from app.service.auth.keycloak_token_provider import KeycloakTokenProvider
                self.token_provider = KeycloakTokenProvider()
            except ImportError:
                self.token_provider = None

    async def refund_credit_async(self, job_uuid: str, company_id: Optional[int] = None) -> bool:
        """
        Gọi POST /api/internal/credits/refund với retry.
        """
        url = f"{self.base_url}/api/internal/credits/refund"
        payload = {"reference": str(job_uuid)}
        if company_id is not None:
            payload["companyId"] = company_id

        headers = {"Content-Type": "application/json"}
        if self.token_provider is not None and hasattr(self.token_provider, "get_service_token"):
            try:
                token = await self.token_provider.get_service_token()
                if token:
                    headers["Authorization"] = f"Bearer {token}"
            except Exception as token_exc:
                logger.warning(f"Could not acquire service token for refund: {token_exc}")

        for attempt in range(1, self.max_retries + 1):
            try:
                if self.http_client is not None:
                    resp = await self.http_client.post(url, json=payload, headers=headers)
                else:
                    async with httpx.AsyncClient(timeout=10.0) as client:
                        resp = await client.post(url, json=payload, headers=headers)

                if resp.status_code == 200:
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

    def refund_credit(self, job_uuid: str, company_id: Optional[int] = None) -> bool:
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
