# S4b-02 · DeliveryRequirement CRUD

| Field | Value |
|-------|-------|
| **Sprint** | 4b |
| **Module** | Delivery Planning |
| **Priority** | 🔴 Must |
| **Label** | `BE` |
| **PRD Ref** | FLOW-2 |
| **Depends on** | S4b-01 |
| **Status** | ✅ Done |

## Mô tả
CompanyManager tạo nhu cầu giao hàng. Mỗi requirement định nghĩa destination, deadline, priority.

## Acceptance Criteria
- [ ] `POST /api/delivery-requirements` — tạo requirement mới
  - Fields: `destination`, `destinationLat`, `destinationLng`, `deadline` (ISO datetime), `priority` (LOW/NORMAL/HIGH/URGENT), `packageIds[]`
  - Validate: deadline phải trong tương lai; packageIds phải thuộc company; tất cả package phải cùng handling_class nếu muốn assign vào cùng trip
- [ ] `GET /api/delivery-requirements` — danh sách (filter: status=PENDING, deadline range)
- [ ] `GET /api/delivery-requirements/{id}` — chi tiết
- [ ] `PATCH /api/delivery-requirements/{id}` — cập nhật deadline, priority
- [ ] `DELETE /api/delivery-requirements/{id}` — chỉ xóa được khi status=PENDING
- [ ] status flow: `PENDING → ASSIGNED (khi gán vào trip) → IN_TRIP (khi trip bắt đầu)`
- [ ] `@PreAuthorize("hasAuthority(''DELIVERY_DEMAND_MANAGE'')")` trên tất cả endpoints
- [ ] Multi-tenancy: chỉ xem requirement của company mình

## Entity mới
```sql
CREATE TABLE delivery_requirements (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES companies(id),
    destination VARCHAR(255) NOT NULL,
    destination_lat DECIMAL(10,7),
    destination_lng DECIMAL(10,7),
    deadline TIMESTAMP NOT NULL,
    priority VARCHAR(20) NOT NULL DEFAULT ''NORMAL'',
    status VARCHAR(20) NOT NULL DEFAULT ''PENDING'',
    created_by BIGINT REFERENCES users(id),
    created_at TIMESTAMP DEFAULT NOW()
);
```

## Files cần tạo
- `entity/DeliveryRequirement.java`
- `repository/DeliveryRequirementRepository.java`
- `service/planning/DeliveryRequirementService.java`
- `controller/planning/DeliveryRequirementController.java`
- `dto/request/DeliveryRequirementRequest.java`
- `dto/response/DeliveryRequirementResponse.java`
