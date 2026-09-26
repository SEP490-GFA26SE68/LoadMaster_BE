# S3-02 · API Validate Trip

| Field | Value |
|-------|-------|
| **Sprint** | 3 |
| **Module** | Optimization |
| **Priority** | 🔴 Must |
| **Label** | `BE` |
| **Status** | ✅ Done |
| **Service** | `OptimizeService` (FastAPI Python) |

## Mô tả
Endpoint validate trip trước khi chạy optimization.

## Acceptance Criteria
- [x] `GET /api/v1/validate/trips/{trip_id}` → 200 + `ValidationResponse`
- [x] Nếu có errors → `can_optimize = false`
- [x] Nếu chỉ có warnings → `can_optimize = true`

## Files cần tạo
- `app/controller/optimization/trip_validation_router.py`

## Notes (Python equivalent)
```python
# Java: TripValidationController.java
# Python: app/controller/optimization/trip_validation_router.py

router = APIRouter(prefix="/api/v1/validate")

@router.get("/trips/{trip_id}", response_model=ApiResponse[ValidationResponse])
async def validate_trip(trip_id: int, db: Session = Depends(get_db)):
    ...
```

## Dependencies
- S3-01 (TripValidationService)

## Implementation
- Router: `OptimizeService/app/controller/optimization/trip_validation_router.py`
- HTTP contract tests: `OptimizeService/tests/test_trip_validation_api.py`
- `trip_id` follows schema v3.4 as `BIGINT` / Python `int`.

## Verification
- `python -m pytest -q` — 93 passed
