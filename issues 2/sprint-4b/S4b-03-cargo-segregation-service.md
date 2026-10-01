# S4b-03 · CargoSegregationService

| Field | Value |
|-------|-------|
| **Sprint** | 4b |
| **Module** | Delivery Planning |
| **Priority** | 🔴 Must |
| **Label** | `BE` |
| **PRD Ref** | FLOW-2 |
| **Depends on** | S3b-01, S4b-01 |
| **Status** | 🔲 Todo |

## Mô tả
System tự phân nhóm packages theo handling_class và detect conflict khi Dispatcher thêm packages vào trip.

## Acceptance Criteria
- [ ] `GET /api/trips/{id}/segregation` — kiểm tra toàn bộ packages trong trip, trả về phân nhóm và conflict
- [ ] Rule: FRAGILE KHÔNG được chung trip với STANDARD (MVP rule)
- [ ] Rule: HAZARDOUS cần xe đặc biệt (cảnh báo nếu vehicle không match)
- [ ] `SegregationResult` chứa: `{ groups: [{handlingClass, packageCount, packageIds}], conflicts: [{ruleCode, message, affectedPackageIds}] }`
- [ ] Khi Dispatcher thêm package vào trip → tự động gọi segregation check → nếu conflict trả lỗi `CARGO_SEGREGATION_CONFLICT`
- [ ] Dispatcher có thể force-add với `override=true` + `overrideReason` → lưu vào `Trip.override_reason`
- [ ] System tự set `Trip.handling_class_lock` khi package đầu tiên được thêm vào trip

## Files cần tạo
- `service/planning/CargoSegregationService.java`
- `dto/response/SegregationResult.java`
