# S3-01 · TripValidationService

| Field | Value |
|-------|-------|
| **Sprint** | 3 |
| **Module** | Optimization |
| **Priority** | 🔴 Must |
| **Label** | `BE` |
| **PRD Ref** | FR-OPT-01 |
| **Status** | ✅ Done |
| **Service** | `OptimizeService` (FastAPI Python) |

## Mô tả
Validate trip trước khi chạy optimization.

## Acceptance Criteria
- [x] Kiểm tra: vehicle đã gán cho trip?
- [x] Kiểm tra: ≥ 1 package trong trip?
- [x] Kiểm tra: tổng weight ≤ `vehicle_type.max_payload_kg`?
- [x] Kiểm tra: tổng volume ≤ `inner_l × inner_w × inner_h`?
- [x] Kiểm tra: mỗi package fit theo các orientation do engine hỗ trợ, dùng `Package.actualLength` và kích thước còn lại từ `PackageType`; schema không lưu `allowedRotations`
- [x] Trả `ValidationResponse { can_optimize, warnings[], errors[] }`

## Files cần tạo
- `app/service/optimize/trip_validation_service.py`
- `app/dto/response/validation_response.py`
- `app/entity/trip_model.py` (mapping schema v3.4 dùng cho validation)
- `tests/test_trip_validation_service_schema_v34.py`

## Verification
- `python -m pytest -q` — 96 passed

## Notes (Python equivalent)
```python
# Java: TripValidationService.java
# Python: app/service/optimize/trip_validation_service.py

class TripValidationService:
    def validate(self, trip_id: int, db: Session) -> ValidationResponse:
        ...
```
