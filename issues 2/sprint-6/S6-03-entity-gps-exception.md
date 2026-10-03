# S6-03 · Entity Migration — GpsTracking + TripException

| Field | Value |
|-------|-------|
| **Sprint** | 6 |
| **Module** | Entity / Migration |
| **Priority** | 🔴 Must |
| **Label** | `BE` `DB` |
| **PRD Ref** | FLOW-6 |
| **Status** | 🔲 Todo |

## Acceptance Criteria — GpsTracking
- [ ] Bảng `gps_tracking`: `id, trip_id FK, latitude DECIMAL(10,7), longitude DECIMAL(10,7), speed_kmh DECIMAL(5,1), heading INT (0-360), recorded_at TIMESTAMP`
- [ ] Index trên `(trip_id, recorded_at)` để query nhanh location mới nhất
- [ ] TTL strategy: records cũ hơn 30 ngày có thể archive (note trong migration, implement bằng cron job sau)

## Acceptance Criteria — TripException
- [ ] Bảng `trip_exceptions`: `id, trip_id FK, exception_type VARCHAR(30), description TEXT, reported_by FK users, resolved BOOLEAN DEFAULT false, new_deadline TIMESTAMP NULL, created_at TIMESTAMP`
- [ ] `exception_type`: TRAFFIC, ACCIDENT, ROAD_CONSTRUCTION, VEHICLE_BREAKDOWN, OTHER

## Files cần tạo
- `src/main/resources/db/migration/V{next}__gps_tracking_trip_exception.sql`
- `entity/GpsTracking.java`
- `entity/TripException.java`
- `constant/ExceptionType.java`
