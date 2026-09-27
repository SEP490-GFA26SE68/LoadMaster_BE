# S3-09 · Lưu kết quả Optimization vào DB

| Field | Value |
|-------|-------|
| **Sprint** | 3 |
| **Module** | Optimization |
| **Priority** | 🔴 Must |
| **Label** | `BE` |
| **PRD Ref** | FR-OPT-04 |
| **Status** | 🔄 Needs schema revalidation |
| **Service** | `OptimizeService` (FastAPI Python) |

## Mô tả
Lưu kết quả từ Engine vào database PostgreSQL (dùng chung schema với LoadMasterService).

## Acceptance Criteria
- [x] INSERT `load_plans` (`job_id`, `plan_version`, `volume_utilization_percent`, `is_approved`, `approved_at`, `approved_by_user_id`)
- [x] INSERT `package_placements` (`load_plan_id`, `package_id`, `pos_x`, `pos_y`, `pos_z`, `loading_sequence`)
- [x] INSERT `unplaced_packages` (`load_plan_id`, `package_id`, `reason_code`)
- [x] UPSERT `center_of_gravity` (`load_plan_id` unique, `cog_x`, `front_axle_load_kg`, `is_axle_overload`)
- [x] UPDATE `optimization_jobs.status = COMPLETED`, `execution_time_ms`
- [x] Không persist `plan_name`, packed dimensions, rotation, count hay weight-utilization vì không có cột tương ứng
- [x] Transaction: tất cả insert trong 1 SQLAlchemy transaction

## Files cần tạo
- `app/repository/optimization/load_plan_repository.py`
- `app/repository/optimization/package_placement_repository.py`
- `app/repository/optimization/unplaced_package_repository.py`
- `app/service/optimize/optimization_result_persistence_service.py`
- `app/entity/optimization/load_plan.py`
- `app/entity/optimization/package_placement.py`
- `app/entity/optimization/unplaced_package.py`

## Notes (Python equivalent)
```python
# Java: OptimizationResultPersistenceServiceImpl.java + @Transactional
# Python: SQLAlchemy transaction via context manager

class OptimizationResultPersistenceService:
    def save(self, job_id: int, result: EngineOptimizationResponse, db: Session) -> LoadPlan:
        with db.begin():
            plan = LoadPlan(job_id=job_id, ...)
            db.add(plan)
            for p in result.placements:
                db.add(PackagePlacement(load_plan_id=plan.id, loading_sequence=p.sequence, ...))
            for u in result.unplaced:
                db.add(UnplacedPackage(load_plan_id=plan.id, reason_code=u.reason, ...))
        return plan
```

## Dependencies
- S3-03 (OptimizationJobService)
- S3-05 (DTO — EngineOptimizationResponse)
