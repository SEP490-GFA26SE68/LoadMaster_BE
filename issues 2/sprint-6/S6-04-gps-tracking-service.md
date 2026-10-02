# S6-04 · GpsTrackingService

| Field | Value |
|-------|-------|
| **Sprint** | 6 |
| **Module** | GPS |
| **Priority** | 🔴 Must |
| **Label** | `BE` |
| **PRD Ref** | FLOW-6 |
| **Depends on** | S6-03 |
| **Status** | 🔲 Todo |

## Acceptance Criteria
- [ ] `POST /api/driver/location` — Driver gửi GPS coordinates
  - Body: `{ tripId, latitude, longitude, speed, heading }`
  - Validate: trip tồn tại, trip status = IN_TRANSIT, driver là driver của trip
  - Lưu `GpsTracking` record
  - Trigger ETA recalculation (async)
  - Broadcast qua WebSocket (S6-10)
- [ ] `GET /api/trips/{id}/location/latest` — lấy vị trí mới nhất
- [ ] `GET /api/trips/{id}/location/history` — lịch sử GPS (phân trang, filter theo thời gian)
- [ ] `@PreAuthorize`: DRIVER được POST, DISPATCHER/COMPANY_MANAGER được GET
- [ ] Rate limit: tối đa 1 request/10s per trip (tránh spam)

## Files cần tạo
- `service/gps/GpsTrackingService.java`
- `controller/gps/GpsTrackingController.java`
- `repository/GpsTrackingRepository.java`
- `dto/request/GpsLocationRequest.java`
