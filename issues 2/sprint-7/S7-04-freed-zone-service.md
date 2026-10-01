# S7-04 · FreedZoneService — Tính vùng trống sau khi giao stop

| Field | Value |
|-------|-------|
| **Sprint** | 7 |
| **Module** | Pickup / Zone |
| **Priority** | 🔴 Must |
| **Label** | `BE` `PYTHON` |
| **PRD Ref** | FLOW-7 |
| **Depends on** | S5b-01 |
| **Status** | 🔲 Todo |

## Acceptance Criteria
- [ ] `GET /api/trips/{id}/freed-zones` — tính và trả về vùng trống hiện tại
  - Load `LoadPlan` approved của trip
  - Xác định các stop đã COMPLETED
  - Zone của stop đã COMPLETED = freed zone
  - Tính available space: `{ zoneStartX, zoneEndX, length, width: vehicle.inner_w, height: vehicle.inner_h, volumeM3, payloadKgAvailable }`
- [ ] Response: `{ freedZones: [{stopId, stopName, zone}], totalFreedVolumeM3, totalFreedWeightKg }`
- [ ] OptimizeService cũng cần API tương tự để nhận freed zone khi re-optimize: `GET /api/v1/trips/{id}/freed-zones`

## Files cần tạo
- `service/pickup/FreedZoneService.java`
- `controller/pickup/FreedZoneController.java`
- `dto/response/FreedZoneResponse.java`
