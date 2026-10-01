# S6-09 · RerouteService

| Field | Value |
|-------|-------|
| **Sprint** | 6 |
| **Module** | Route |
| **Priority** | 🟡 Should |
| **Label** | `BE` |
| **PRD Ref** | FLOW-6 |
| **Depends on** | S4b-04, S6-03 |
| **Status** | 🔲 Todo |

## Acceptance Criteria
- [ ] `POST /api/trips/{id}/reroute` — Dispatcher yêu cầu tính lại route khi có road incident
  - Body: `{ currentLat, currentLng, avoidPolyline (optional) }`
  - Gọi Goong Directions với `avoid` param nếu có
  - Trả về các route alternatives: `[{ duration, distance, summary, polyline }]`
  - Dispatcher chọn 1 route → `POST /api/trips/{id}/reroute/{routeIndex}/confirm`
  - Lưu route mới vào `Trip.route_plan`, recalculate ETA
- [ ] Nếu không có route thay thế đáp ứng deadline → response kèm `no_feasible_route: true`
- [ ] Ghi AuditLog action_type=`TRIP_REROUTED`

## Files cần tạo
- `service/route/RerouteService.java`
- `controller/route/RerouteController.java`
- `dto/route/RerouteRequest.java`, `RerouteAlternative.java`
