# S3-03 · OptimizationJob Service

| Field | Value |
|-------|-------|
| **Sprint** | 3 |
| **Module** | Optimization |
| **Priority** | 🔴 Must |
| **Label** | `BE` |
| **PRD Ref** | FR-OPT-02 |
| **Status** | 🔄 Needs schema revalidation |
| **Service** | `OptimizeService` (FastAPI Python) |

## Mô tả
Service tạo và quản lý OptimizationJob.

## Acceptance Criteria
- [x] `create_job(trip_id, config)` → INSERT `optimization_jobs` với `BIGSERIAL` id, `algorithm_objective` và status `PENDING`
- [x] Status flow: `PENDING` → `RUNNING` → `COMPLETED` / `FAILED` / `TIMEOUT` / `NO_SOLUTION`
- [x] `get_job(job_id)` → trả `id`, `tripId`, `algorithmObjective`, `executionTimeMs`, `status`
- [x] SQLAlchemy queries: `find_by_trip_id()`, `find_by_status()`

## Files cần tạo
- `app/repository/optimization/optimization_job_repository.py`
- `app/service/optimize/optimization_job_service.py`
- `app/entity/optimization/optimization_job.py`

## Notes (Python equivalent)
```python
# Java: OptimizationJobService.java + OptimizationJobRepository.java
# Python: app/service/optimize/optimization_job_service.py

class OptimizationJobService:
    def create_job(self, trip_id: int, config: OptimizationRequest, db: Session) -> OptimizationJob:
        job = OptimizationJob(trip_id=trip_id, algorithm_objective=config.objective,
                              status=JobStatus.PENDING)
        db.add(job)
        db.commit()
        return job
```
