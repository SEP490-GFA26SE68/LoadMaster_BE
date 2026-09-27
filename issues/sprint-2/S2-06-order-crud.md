# S2-06 · Order CRUD

| Field | Value |
|-------|-------|
| **Sprint** | 2 |
| **Module** | Order & Package |
| **Priority** | 🔴 Must |
| **Label** | `BE` |
| **PRD Ref** | FR-ORD-01 |
| **Status** | 🔲 Todo |

## Mô tả
CRUD `Order` theo bảng `orders` — đơn vận chuyển gắn khách hàng và điểm giao (nullable).

## Acceptance Criteria
- [ ] `OrderRepository` — `findByCompanyId()`, `findByCustomerId()`, `findByDeliveryStopId()`
- [ ] `OrderService` — create (auto-gen `orderCode`), update, getById, getAll (paging + filter)
- [ ] `OrderController` — `GET/POST/PUT /api/orders`
- [ ] Validate: `customerId` tồn tại và thuộc cùng company; `deliveryStopId` nullable
- [ ] Auto-gen `orderCode` format: `ORD-{company.code}-{yyyyMMdd}-{seq}`
- [ ] Persist đúng: `companyId`, `orderCode`, `customerId`, `deliveryStopId`, `totalWeightKg`
- [ ] `totalWeightKg` mặc định `0.00` và được tính lại từ tổng `Package.actualWeightKg`; không có status/time window trong schema

## Files cần tạo
- `repository/OrderRepository.java`
- `service/OrderService.java`
- `controller/OrderController.java`
- `dto/request/OrderRequest.java`
- `dto/response/OrderResponse.java`

## Dependencies
- S2-04 (Customer CRUD)
