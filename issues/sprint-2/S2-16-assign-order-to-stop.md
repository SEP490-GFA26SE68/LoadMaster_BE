# S2-16 · Gán Order vào Stop

| Field | Value |
|-------|-------|
| **Sprint** | 2 |
| **Module** | Trip & Stop |
| **Priority** | 🔴 Must |
| **Label** | `BE` |
| **PRD Ref** | FR-TRIP-03 |
| **Status** | 🔲 Todo |

## Mô tả
Gắn đơn hàng vào điểm dừng giao hàng.

## Acceptance Criteria
- [ ] `PUT /api/orders/{id}/assign-stop` — body `{ deliveryStopId: 5 }`
- [ ] Validate: stop thuộc cùng company
- [ ] Validate: order chưa gán stop khác (hoặc cho phép gán lại)
- [ ] Update `Order.deliveryStopId` (`orders.delivery_stop_id`, nullable, `ON DELETE SET NULL`)

## Dependencies
- S2-06 (Order), S2-15 (DeliveryStop)
