# S6-10 · WebSocket GPS Broadcast

| Field | Value |
|-------|-------|
| **Sprint** | 6 |
| **Module** | GPS / WebSocket |
| **Priority** | 🔴 Must |
| **Label** | `BE` |
| **PRD Ref** | FLOW-6 |
| **Depends on** | S6-04 |
| **Status** | 🔲 Todo |

## Acceptance Criteria
- [ ] WebSocket endpoint: `ws://host/ws/trips/{tripId}/monitoring`
  - Authentication: query param `?token=<JWT>` (fix TODO trong Sprint 1)
  - Chỉ DISPATCHER và COMPANY_MANAGER của cùng company được subscribe
- [ ] Mỗi khi Driver gửi GPS → broadcast `LocationUpdate { lat, lng, speed, heading, timestamp }`
- [ ] Mỗi khi ETA recalculate → broadcast `EtaUpdate { stops: [...] }`
- [ ] Mỗi khi có ETA risk → broadcast `EtaRiskAlert { stopId, delayMinutes, deadline, newEta }`
- [ ] Khi trip DELIVERED → broadcast `TripCompleted`, đóng connection
- [ ] Spring Boot: dùng `@EnableWebSocket` + `WebSocketHandler` hoặc STOMP

## Files cần tạo
- `config/WebSocketConfig.java` (mở rộng)
- `websocket/TripMonitoringWebSocketHandler.java`
- `dto/websocket/LocationUpdate.java`
- `dto/websocket/EtaUpdate.java`
- `dto/websocket/EtaRiskAlert.java`
