# S5b-06 · TripValidationService Upgrade

| Field | Value |
|-------|-------|
| **Sprint** | 5b |
| **Module** | Optimize / Validation |
| **Priority** | 🟡 Should |
| **Label** | `PYTHON` |
| **PRD Ref** | FLOW-3 |
| **Depends on** | S5b-01 |
| **Status** | 🔲 Todo |

## Mô tả
Nâng cấp `TripValidationService` (đã có) để validate thêm: handling class consistency, COG feasibility sơ bộ.

## Acceptance Criteria — thêm mới
- [ ] AC6: Tất cả packages trong trip có cùng handling_class (hoặc trip có override_reason)
- [ ] AC7: Sơ bộ COG estimate — nếu tất cả hàng nặng đều ở đúng một phía → warning `COG_RISK`
- [ ] AC8: Mỗi stop phải có `lat/lng` — nếu thiếu → error `MISSING_STOP_COORDINATES` (cần cho route opt)
- [ ] AC9: `max_stack_weight_kg` — kiểm tra không có package nào bị xếp lên package fragile với weight > max_stack_weight

## Files cần sửa
- `app/service/optimize/trip_validation_service.py`
