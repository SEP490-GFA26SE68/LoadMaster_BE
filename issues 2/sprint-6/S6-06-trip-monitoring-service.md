# S6-06 · TripMonitoringService

| Field | Value |
|-------|-------|
| **Sprint** | 6 |
| **Module** | Monitoring |
| **Priority** | 🔴 Must |
| **Label** | `BE` |
| **PRD Ref** | FLOW-6 |
| **Depends on** | S6-05 |
| **Status** | 🔲 Todo |

## Acceptance Criteria
- [ ] Sau mỗi ETA recalculation: nếu `atRisk=true` → gửi alert qua WebSocket đến Dispatcher
- [ ] Alert message: `{ type: "ETA_RISK", tripId, stopId, deadline, newEta, delayMinutes }`
- [ ] Dispatcher WebSocket endpoint: `ws://host/ws/trips/{tripId}/monitoring`
- [ ] Dispatcher có thể theo dõi nhiều trip cùng lúc
- [ ] `GET /api/trips/{id}/monitoring` — snapshot hiện tại: vị trí, ETA từng stop, risk status

## Files cần tạo
- `service/monitoring/TripMonitoringService.java`
- `controller/monitoring/TripMonitoringController.java`
- WebSocket handler trong `main.java` hoặc `WebSocketConfig.java`
