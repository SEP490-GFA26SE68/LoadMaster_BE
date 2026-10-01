# S6-05 · EtaCalculationService

| Field | Value |
|-------|-------|
| **Sprint** | 6 |
| **Module** | Route / ETA |
| **Priority** | 🔴 Must |
| **Label** | `BE` |
| **PRD Ref** | FLOW-6 |
| **Depends on** | S4b-04, S6-04 |
| **Status** | 🔲 Todo |

## Acceptance Criteria
- [ ] `recalculateEta(Long tripId, LatLng currentPosition)` → `EtaResult`
  - Xác định stop hiện tại (stop đầu tiên chưa COMPLETED)
  - Gọi Goong Directions từ `currentPosition` đến next stop → duration
  - Tính ETA từng stop tiếp theo = ETA_prev + travel_time + service_time
  - Cập nhật `DeliveryStop.planned_arrival` cho các stop chưa COMPLETED
  - Nếu ETA > deadline → set `at_risk=true` trong result
- [ ] `EtaResult`: `{ stops: [{stopId, deadline, newEta, atRisk}], riskCount: int }`
- [ ] Chạy async (triggered bởi GPS update, không block response GPS)
- [ ] Cache: không gọi Goong quá 1 lần / 1 phút / 1 trip (tránh cost API)

## Files cần tạo
- `service/route/EtaCalculationService.java`
- `dto/route/EtaResult.java`
