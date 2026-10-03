# S6-08 · TripExceptionService

| Field | Value |
|-------|-------|
| **Sprint** | 6 |
| **Module** | Exception Handling |
| **Priority** | 🔴 Must |
| **Label** | `BE` |
| **PRD Ref** | FLOW-6 |
| **Depends on** | S6-03 |
| **Status** | 🔲 Todo |

## Acceptance Criteria
- [ ] `POST /api/trips/{id}/exceptions` — Driver/Dispatcher ghi nhận exception
  - Body: `{ exceptionType, description }`
  - Tạo `TripException` record
  - Ghi AuditLog
  - Notify Dispatcher qua WebSocket
- [ ] `POST /api/trips/{id}/exceptions/{eid}/resolve` — Dispatcher đánh dấu resolved + new_deadline (optional)
- [ ] Nếu có `new_deadline` → cập nhật `DeliveryRequirement.deadline` cho stop liên quan → trigger ETA recalculation
- [ ] `GET /api/trips/{id}/exceptions` — lịch sử exceptions của trip
- [ ] Escalation: nếu exception unresolved > 30 phút → notify CompanyManager (WebSocket / email)

## Files cần tạo
- `service/exception/TripExceptionService.java`
- `controller/exception/TripExceptionController.java`
- `repository/TripExceptionRepository.java`
