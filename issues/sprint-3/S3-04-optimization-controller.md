# S3-04 · OptimizationController

| Field | Value |
|-------|-------|
| **Sprint** | 3 |
| **Module** | Optimization |
| **Priority** | 🔴 Must |
| **Label** | `BE` |
| **Status** | 🔄 Needs schema revalidation |
| **Service** | `OptimizeService` (FastAPI Python) |

## Mô tả
APIRouter cho optimization endpoints — **React frontend gọi vào FastAPI trực tiếp** (thay vì qua Spring Boot).

> **Lưu ý kiến trúc đã thay đổi:**
> - **Trước:** React → Spring Boot → FastAPI
> - **Sau:** React → FastAPI trực tiếp (Spring Boot không còn optimize nữa)
>
> FastAPI tự verify user JWT token (Keycloak) và check role `DISPATCHER` / `ADMIN`.

## Acceptance Criteria
- [x] `POST /api/v1/optimization/jobs` — body `{ trip_id, objective, time_limit_sec, seed }` → 202 Accepted + numeric `{ job_id }`
- [x] `GET /api/v1/optimization/jobs/{id}` → status, metadata
- [x] `GET /api/v1/optimization/jobs/{id}/plans` → LoadPlan + placements
- [x] Chỉ `DISPATCHER` / `ADMIN` mới được gọi (FastAPI verify JWT role)

## Files cần tạo
- `app/controller/optimization/optimization_router.py`
- `app/dto/request/optimization_request.py`

## Notes (Python equivalent)
```python
# Java: OptimizationController.java
# Python: app/controller/optimization/optimization_router.py

router = APIRouter(prefix="/api/v1/optimization")

@router.post("/jobs", status_code=202)
async def submit_job(
    request: OptimizationRequest,
    background_tasks: BackgroundTasks,
    db: Session = Depends(get_db),
    current_user = Depends(require_role("DISPATCHER", "ADMIN"))
):
    job = job_service.create_job(request.trip_id, request, db)
    background_tasks.add_task(runner.run, job.id)
    return {"job_id": job.id}
```

## Dependencies
- S3-03 (OptimizationJobService)
- S3-08 (AsyncOptimizationRunner)
