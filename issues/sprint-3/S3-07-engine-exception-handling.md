# S3-07 · Xử lý ngoại lệ Engine

| Field | Value |
|-------|-------|
| **Sprint** | 3 |
| **Module** | Optimization |
| **Priority** | 🔴 Must |
| **Label** | `BE` |
| **PRD Ref** | SD01b |
| **Status** | 🔄 Needs schema revalidation |
| **Service** | `OptimizeService` (FastAPI Python) |

## Mô tả
Handle tất cả exception từ Optimization Engine (engine ngoài hoặc lỗi nội bộ).

## Acceptance Criteria
- [x] Connection refused / network error → update job `FAILED`, log `SERVICE_UNAVAILABLE`
- [x] Timeout (`httpx.TimeoutException`) → update job `TIMEOUT`, log time limit exceeded
- [x] 0 placements → update job `NO_SOLUTION`, lưu unplaced + reasons
- [x] Partial (some unplaced) → lưu `LoadPlan` cùng các `UnplacedPackage`, cập nhật `OptimizationJob.status = PARTIAL`; không dùng `LoadPlan.status`
- [x] **Engine trả 401** (token thiếu/sai/expired/sai audience) → update job `FAILED`, log `AUTH_ERROR`
- [x] **Engine trả 403** (thiếu scope `optimization.execute`) → update job `FAILED`, log `AUTH_FORBIDDEN`
- [x] Tất cả exception → ghi log, update job status, không crash server

## Files cần tạo
- `app/service/optimize/engine_exception_handler.py`
- `app/exception/error_code.py`
- `app/exception/global_exception_handler.py`

## Notes (Python equivalent)
```python
# Java: EngineExceptionHandlerImpl.java
# Python: app/service/optimize/engine_exception_handler.py

class EngineExceptionHandler:
    def handle(self, exc: Exception, job_id: int, db: Session) -> None:
        if isinstance(exc, httpx.TimeoutException):
            self._update_job(job_id, JobStatus.TIMEOUT, db)
        elif isinstance(exc, httpx.HTTPStatusError) and exc.response.status_code == 401:
            self._update_job(job_id, JobStatus.FAILED, db)
            logger.error("AUTH_ERROR: ...")
        ...
```

## Dependencies
- S3-06 (OptimizationClient)
- S3-06b (KeycloakTokenProvider)
