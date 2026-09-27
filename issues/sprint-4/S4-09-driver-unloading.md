# S4-09 · Driver: Complete Trip (schema-compatible)

| Field | Value |
|-------|-------|
| **Sprint** | 4 |
| **Module** | Driver Unloading |
| **Priority** | 🔴 Must |
| **Label** | `BE` |
| **PRD Ref** | FR-DRV-03 → FR-DRV-06 |
| **Status** | 🔄 Needs schema revalidation |

## Acceptance Criteria

### Complete Trip
- [x] `POST /api/driver/trips/{id}/complete`
- [x] Validate trip được gán cho driver qua `Vehicle.driverUserId`, thuộc trạng thái cho phép hoàn tất
- [x] Set `Trip.status = DELIVERED`
- [x] INSERT AuditLog `TRIP_COMPLETED`
- [x] Không cung cấp confirm-unload/complete-stop có persistence vì `packages` và `delivery_stops` không có unload/status field, và schema không có `UnloadConfirmation`

## Dependencies
- S4-08 (Get Trips/Stop Manifest)
