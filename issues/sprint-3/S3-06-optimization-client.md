# S3-06 · OptimizationClient — Gọi Engine Nội Bộ

| Field | Value |
|-------|-------|
| **Sprint** | 3 |
| **Module** | Optimization |
| **Priority** | 🔴 Must |
| **Label** | `BE` |
| **Status** | ✅ Done |
| **Service** | `OptimizeService` (FastAPI Python) |

## Mô tả
Service gọi tới **Optimization Engine thứ 3** (nếu có engine ngoài).

> **Lưu ý kiến trúc đã thay đổi:**
> - Trước: Spring Boot gọi FastAPI (OptimizeService) với service token Keycloak
> - Sau: **FastAPI (OptimizeService) chính là engine** — không cần gọi service ngoài nữa
>
> Nếu sau này có engine ngoài thì file này sẽ implement `httpx.AsyncClient` để gọi ra.

## Acceptance Criteria
- [x] Interface/class: `OptimizationClient.solve(problem: ProblemRequest) → EngineOptimizationResponse`
- [x] Hiện tại: implement in-process (thuật toán nội bộ hoặc placeholder)
- [x] Nếu engine ngoài: dùng `httpx.AsyncClient`, gắn Bearer token từ `KeycloakTokenProvider`
- [x] URL engine cấu hình qua `.env`: `OPTIMIZATION_ENGINE_URL`
- [x] Timeout cấu hình: `OPTIMIZATION_ENGINE_TIMEOUT_SEC`

## Files cần tạo
- `app/service/optimize/optimization_client.py`

## Notes (Python equivalent)
```python
# Java: HttpOptimizationClient.java
# Python: app/service/optimize/optimization_client.py

class OptimizationClient:
    async def solve(self, problem: ProblemRequest) -> EngineOptimizationResponse:
        async with httpx.AsyncClient(timeout=settings.engine_timeout_sec) as client:
            token = await keycloak_provider.get_service_token()
            resp = await client.post(
                f"{settings.engine_url}/api/v1/optimization/jobs",
                json=problem.model_dump(),
                headers={"Authorization": f"Bearer {token}"}
            )
            resp.raise_for_status()
            return EngineOptimizationResponse(**resp.json())
```

## Dependencies
- S3-05 (DTO)
- S3-06b (KeycloakServiceTokenProvider)
