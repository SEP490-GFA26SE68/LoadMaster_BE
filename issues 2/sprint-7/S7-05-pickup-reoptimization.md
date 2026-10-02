# S7-05 · PickupReoptimizationService — Re-optimize Freed Zone

| Field | Value |
|-------|-------|
| **Sprint** | 7 |
| **Module** | Pickup / Optimize |
| **Priority** | 🔴 Must |
| **Label** | `BE` `PYTHON` |
| **PRD Ref** | FLOW-7 |
| **Depends on** | S7-04, S5b-04 |
| **Status** | 🔲 Todo |

## Acceptance Criteria
- [ ] Khi pickup approved → trigger async re-optimization của freed zone
- [ ] OptimizeService nhận `ReoptimizeFreedZoneRequest`:
  - `freedZone`: dimensions của vùng trống
  - `pickupPackages`: danh sách package mới cần xếp
  - `existingPlacements`: placements hiện tại (để tránh collision, KHÔNG di chuyển)
- [ ] Re-optimize chỉ freed zone, không động đến existing placements
- [ ] Kết quả: `PackagePlacement` mới cho pickup packages trong freed zone
- [ ] Trả về `ReoptimizeResult { placements, unplacedPickups, cogDelta, axleLoadDelta }`
- [ ] Nếu pickup packages không fit → `unplacedPickups` list → Dispatcher decide
- [ ] Driver nhận placement mới qua WebSocket → scan QR → xếp vào vị trí chỉ định
- [ ] `POST /api/v1/optimize/reoptimize-freed-zone` — endpoint mới trong OptimizeService
- [ ] Ghi AuditLog action_type=`PICKUP_REOPTIMIZED`

## Files cần tạo (OptimizeService)
- `app/controller/optimization/reoptimize_freed_zone_router.py`
- `app/service/optimize/freed_zone_reoptimizer.py`
- `app/dto/optimization/engine/reoptimize_request.py`
