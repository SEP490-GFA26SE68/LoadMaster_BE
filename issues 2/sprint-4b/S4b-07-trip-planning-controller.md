# S4b-07 · TripPlanningController

| Field | Value |
|-------|-------|
| **Sprint** | 4b |
| **Module** | Trip Planning |
| **Priority** | 🔴 Must |
| **Label** | `BE` |
| **PRD Ref** | FLOW-2 |
| **Depends on** | S4b-03, S4b-06 |
| **Status** | 🔲 Todo |

## Mô tả
Các API cho Dispatcher lập trip: tạo trip, thêm packages, xem phân nhóm, chạy route optimization.

## Acceptance Criteria
- [ ] `POST /api/trips` — tạo trip với vehicle, driver (status=DRAFT)
- [ ] `POST /api/trips/{id}/packages` — thêm packages vào trip (kèm segregation check)
- [ ] `DELETE /api/trips/{id}/packages/{packageId}` — xóa package khỏi trip
- [ ] `GET /api/trips/{id}/segregation` — xem phân nhóm cargo + conflict
- [ ] `POST /api/trips/{id}/optimize-route` — chạy route optimization → trả `RouteOptimizationResult`
- [ ] `GET /api/trips/{id}/eta` — ETA hiện tại theo traffic (gọi lại Goong với thời điểm hiện tại)
- [ ] `@PreAuthorize("hasAuthority(''TRIP_MANAGE'')")` trên tất cả
- [ ] Multi-tenancy: trip phải thuộc company của Dispatcher

## Files cần tạo
- `controller/planning/TripPlanningController.java`
- `dto/request/AddPackagesToTripRequest.java`
- `dto/response/TripDetailResponse.java`
