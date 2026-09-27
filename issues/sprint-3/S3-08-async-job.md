# S3-08 · Async Job Execution

| Field | Value |
|-------|-------|
| **Sprint** | 3 |
| **Module** | Optimization |
| **Priority** | 🔴 Must |
| **Label** | `BE` |
| **Status** | 🔄 Needs schema revalidation |
| **Service** | `OptimizeService` (FastAPI Python) |

## Mô tả
Chạy optimization bất đồng bộ để không block HTTP request.

## Acceptance Criteria
- [x] `POST /api/v1/optimization/jobs` trả `202 Accepted` + `{ job_id }` ngay lập tức
- [x] Job chạy background qua `BackgroundTasks` của FastAPI (tương đương `@Async` Spring)
- [x] Update job status `RUNNING` khi bắt đầu, `COMPLETED`/`FAILED`/`TIMEOUT`/`NO_SOLUTION`/`PARTIAL` khi xong
- [x] Frontend poll `GET /api/v1/optimization/jobs/{id}` để kiểm tra status

## Files cần tạo
- `app/service/optimize/async_optimization_runner.py`

## Notes (Python equivalent)
```python
# Java: AsyncOptimizationRunnerImpl.java + AsyncConfig.java
# Python: FastAPI BackgroundTasks (không cần config thread pool riêng)

# app/service/optimize/async_optimization_runner.py
class AsyncOptimizationRunner:
    async def run(self, job_id: int, problem: ProblemRequest) -> None:
        # chạy trong background, không block HTTP response
        try:
            job_service.update_status(job_id, JobStatus.RUNNING)
            result = await optimization_client.solve(problem)
            persistence_service.save(job_id, result)
            job_service.update_status(job_id, JobStatus.COMPLETED)
        except Exception as e:
            engine_exception_handler.handle(e, job_id)

# app/controller/optimization/optimization_router.py
@router.post("/jobs", status_code=202)
async def submit_job(req: OptimizationRequest, background_tasks: BackgroundTasks, ...):
    job = job_service.create_job(req.trip_id, req, db)
    background_tasks.add_task(runner.run, job.id, problem)
    return {"job_id": job.id}
```

## Dependencies
- S3-03 (OptimizationJobService)
- S3-06 (OptimizationClient)
