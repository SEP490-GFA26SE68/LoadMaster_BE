# S2-07 · Package CRUD

| Field | Value |
|-------|-------|
| **Sprint** | 2 |
| **Module** | Order & Package |
| **Priority** | 🔴 Must |
| **Label** | `BE` |
| **PRD Ref** | FR-ORD-03 |
| **Status** | 🔲 Todo |

## Mô tả
CRUD `Package` — kiện hàng vật lý cụ thể thuộc một `Order`.

## Acceptance Criteria
- [ ] `PackageRepository` — `findByOrderId()`, `findByPackageTypeId()`, `findByTrackingBarcode()`
- [ ] `PackageService` — CRUD, tự tính lại `Order.totalWeightKg` khi thêm/sửa/xóa package
- [ ] `PackageController` — `GET/POST/PUT/DELETE /api/packages`
- [ ] Gắn vào `orderId` + `packageTypeId`
- [ ] Fields: `orderId`, `packageTypeId`, `trackingBarcode`, `actualLength`, `actualWeightKg`, `isPinned`
- [ ] Validate unique `trackingBarcode`; `actualLength > 0`; `actualWeightKg > 0`

## Files cần tạo
- `repository/PackageRepository.java`
- `service/PackageService.java`
- `controller/PackageController.java`
- `dto/request/PackageRequest.java`
- `dto/response/PackageResponse.java`

## Dependencies
- S2-06 (Order), S2-05 (PackageType)
