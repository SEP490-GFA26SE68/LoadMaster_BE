# S4b-03 · CargoSegregationService

| Field | Value |
|-------|-------|
| **Sprint** | 4b |
| **Module** | Delivery Planning |
| **Priority** | 🔴 Must |
| **Label** | `BE` |
| **PRD Ref** | FLOW-2 |
| **Depends on** | S3b-01, S4b-01 |
| **Status** | ✅ Done |

## Mô tả
System tự phân nhóm packages theo handling_class và detect conflict khi Dispatcher thêm packages vào trip.

## Acceptance Criteria
- [x] `GET /api/trips/{id}/segregation` — kiểm tra toàn bộ packages trong trip, trả về phân nhóm và conflict
- [x] Rule: FRAGILE KHÔNG được chung trip với STANDARD (MVP rule)
- [x] Rule: HAZARDOUS cần xe đặc biệt (cảnh báo nếu vehicle không match)
- [x] `SegregationResult` chứa: `{ groups: [{handlingClass, packageCount, packageIds}], conflicts: [{ruleCode, message, affectedPackageIds}] }`
- [x] Khi Dispatcher thêm package vào trip → tự động gọi segregation check → nếu conflict trả lỗi `CARGO_SEGREGATION_CONFLICT`
- [x] Dispatcher có thể force-add với `override=true` + `overrideReason` → lưu vào `Trip.override_reason`
- [x] System tự set `Trip.handling_class_lock` khi package đầu tiên được thêm vào trip

## Files cần tạo
- `service/planning/CargoSegregationService.java`
- `dto/response/SegregationResult.java`
