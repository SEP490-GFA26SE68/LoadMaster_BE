# S2-15 · DeliveryStop CRUD

| Field | Value |
|-------|-------|
| **Sprint** | 2 |
| **Module** | Trip & Stop |
| **Priority** | 🔴 Must |
| **Label** | `BE` |
| **PRD Ref** | FR-TRIP-02 |
| **Status** | 🔲 Todo |

## Mô tả
CRUD DeliveryStop — điểm dừng giao hàng trong chuyến đi.

## Acceptance Criteria
- [ ] `DeliveryStopRepository` — `findByTripIdOrderByStopSequence()`
- [ ] `DeliveryStopService` — CRUD, validate unique `stopSequence` per trip
- [ ] `DeliveryStopController` — `GET/POST/PUT/DELETE /api/trips/{tripId}/stops`
- [ ] Nested resource: stops thuộc trip
- [ ] Auto-reorder sequence khi xóa stop ở giữa
- [ ] DTO/persistence chỉ gồm `id`, `tripId`, `stopSequence`, `locationAddress`; không dùng `status`, `stopName`, tọa độ hoặc thời gian

## Files cần tạo
- `repository/DeliveryStopRepository.java`
- `service/DeliveryStopService.java`
- `controller/DeliveryStopController.java`
- `dto/request/DeliveryStopRequest.java`
- `dto/response/DeliveryStopResponse.java`

## Dependencies
- S2-14 (Trip CRUD)
