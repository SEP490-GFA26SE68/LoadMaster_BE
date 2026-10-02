# S7-02 · PickupRequestService — Validate 10 Rules

| Field | Value |
|-------|-------|
| **Sprint** | 7 |
| **Module** | Pickup |
| **Priority** | 🔴 Must |
| **Label** | `BE` |
| **PRD Ref** | FLOW-7 |
| **Depends on** | S7-01, S6-04 |
| **Status** | 🔲 Todo |

## Acceptance Criteria
- [ ] `POST /api/trips/{id}/pickup-requests` — tạo pickup request (Driver hoặc Dispatcher)
  - Body: `{ pickupPoint, pickupLat, pickupLng, destination, destinationLat, destinationLng, packages: [{lengthMm, widthMm, heightMm, weightKg, handlingClass}] }`
  - Trip phải ở trạng thái IN_TRANSIT
- [ ] `GET /api/trips/{id}/pickup-requests/{pid}/validate` — system tự validate 10 rules:
  1. Pickup point ≤ 10km lệch khỏi route hiện tại (dùng Goong)
  2. Destination không vượt qua next protected stop
  3. Còn payload capacity (tổng weight hiện tại + pickup ≤ max_payload)
  4. Còn physical space (freed zone volume đủ)
  5. Axle load hợp lệ sau khi thêm
  6. COG hợp lệ sau khi thêm
  7. Stacking hợp lệ (không có fragile bị đè)
  8. Handling class tương thích (không mix FRAGILE với STANDARD trừ khi trip có override)
  9. Deadline mới khả thi (ETA pickup destination ≤ deadline)
  10. Không block cargo hiện tại (LIFO check: freed zone không có hàng chưa giao)
- [ ] Trả về `{ rulesPassed: [1,2,3,...], rulesFailed: [{ruleId, ruleCode, message}] }`
- [ ] `POST /api/trips/{id}/pickup-requests/{pid}/approve` — Dispatcher approve
  - Chỉ approve khi tất cả 10 rules pass (hoặc Dispatcher force với `override=true`)
  - Tạo `PickupPackage` cho mỗi package kèm qr_token mới
  - Trigger re-optimization (S7-05)
- [ ] `@PreAuthorize`: DRIVER được tạo, DISPATCHER được approve

## Files cần tạo
- `service/pickup/PickupRequestService.java`
- `service/pickup/PickupValidationService.java`
- `controller/pickup/PickupRequestController.java`
- `dto/request/PickupRequestBody.java`
- `dto/response/PickupValidationResult.java`
