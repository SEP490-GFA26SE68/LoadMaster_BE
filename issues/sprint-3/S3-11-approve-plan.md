# S3-11 · API Approve Plan

| Field | Value |
|-------|-------|
| **Sprint** | 3 |
| **Module** | Optimization |
| **Priority** | 🔴 Must |
| **Label** | `BE` |
| **PRD Ref** | FR-OPT-07 |
| **Status** | 🔄 Needs schema revalidation |
| **Service** | `OptimizeService` (FastAPI Python) |

## Mô tả
Dispatcher duyệt kế hoạch xếp hàng.

## Acceptance Criteria
- [x] `POST /api/v1/load-plans/{id}/approve`
- [x] Verify: plan complete (có placements), LIFO valid (`loading_sequence` không bị duplicate)
- [x] Set `is_approved = True`, `approved_at = now()`, `approved_by_user_id = current_user.id`
- [x] INSERT `audit_logs` với action `PLAN_APPROVED`
- [x] Trả 200 OK + `LoadPlanResponse`

## Files cần tạo
- `app/controller/optimization/load_plan_router.py`
- `app/service/optimize/load_plan_service.py`
- `app/repository/optimization/load_plan_repository.py`

## Notes (Python equivalent)
```python
# Java: LoadPlanController.java + LoadPlanServiceImpl.java
# Python: load_plan_router.py + load_plan_service.py

# app/service/optimize/load_plan_service.py
class LoadPlanService:
    def approve_plan(self, plan_id: int, current_user_id: int, db: Session) -> LoadPlan:
        plan = self._get_or_raise(plan_id, db)
        if plan.is_approved:
            raise AppException(ErrorCode.PLAN_ALREADY_APPROVED)
        if not plan.package_placements:
            raise AppException(ErrorCode.PLAN_HAS_NO_PLACEMENTS)
        if not self._validate_lifo(plan.package_placements):
            raise AppException(ErrorCode.PLAN_LIFO_INVALID)
        plan.is_approved = True
        plan.approved_at = datetime.now()
        plan.approved_by_user_id = current_user_id
        db.add(AuditLog(action="PLAN_APPROVED", entity_id=plan_id, ...))
        db.commit()
        return plan

    @staticmethod
    def _validate_lifo(placements: list[PackagePlacement]) -> bool:
        sequences = [p.loading_sequence for p in placements]
        return len(sequences) == len(set(sequences))  # no duplicates

# app/controller/optimization/load_plan_router.py
@router.post("/load-plans/{plan_id}/approve")
def approve_plan(plan_id: int, db: Session = Depends(get_db),
                 current_user = Depends(require_role("DISPATCHER", "ADMIN"))):
    plan = load_plan_service.approve_plan(plan_id, current_user.id, db)
    return ApiResponse.ok(LoadPlanResponse.from_orm(plan))
```

## Dependencies
- S3-09 (LoadPlan, PackagePlacement entities)
