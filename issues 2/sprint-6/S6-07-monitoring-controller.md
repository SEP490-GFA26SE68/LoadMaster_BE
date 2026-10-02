# S6-07 · TripMonitoringController — Dispatcher Dashboard API

| Field | Value |
|-------|-------|
| **Sprint** | 6 |
| **Module** | Monitoring |
| **Priority** | 🟡 Should |
| **Label** | `BE` |
| **PRD Ref** | FLOW-6 |
| **Depends on** | S6-06 |
| **Status** | 🔲 Todo |

## Acceptance Criteria
- [ ] `GET /api/dispatcher/dashboard` — tất cả trips IN_TRANSIT của company, kèm ETA status
- [ ] `GET /api/trips/{id}/monitoring` — chi tiết: vị trí hiện tại, ETA từng stop, risk flags
- [ ] `@PreAuthorize("hasAnyAuthority(''DISPATCHER'',''COMPANY_MANAGER'')")`
- [ ] Response `TripMonitoringResponse`: `{ tripId, tripCode, driverName, currentLocation: {lat,lng}, stops: [{stopId, name, deadline, eta, atRisk, status}] }`

## Files cần tạo
- `controller/monitoring/TripMonitoringController.java`
- `dto/response/TripMonitoringResponse.java`
