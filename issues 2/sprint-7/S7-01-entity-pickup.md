# S7-01 · Entity Migration — PickupRequest & PickupPackage

| Field | Value |
|-------|-------|
| **Sprint** | 7 |
| **Module** | Entity / Migration |
| **Priority** | 🔴 Must |
| **Label** | `BE` `DB` |
| **PRD Ref** | FLOW-7 |
| **Status** | 🔲 Todo |

## Mô tả
Tạo entity cho pickup request và pickup package. Package pickup cần record trong DB trước khi Driver xếp lên xe (Fix B3 + B8).

## Acceptance Criteria — PickupRequest
- [ ] Bảng `pickup_requests`: `id, trip_id FK, pickup_point VARCHAR(255), pickup_lat DECIMAL(10,7), pickup_lng DECIMAL(10,7), destination VARCHAR(255), destination_lat DECIMAL(10,7), destination_lng DECIMAL(10,7), status VARCHAR(20), validation_errors JSON, approved_by FK users, created_at TIMESTAMP`
- [ ] `status`: PENDING, VALIDATED, APPROVED, REJECTED

## Acceptance Criteria — PickupPackage (Fix B3 + B8)
- [ ] Bảng `pickup_packages`: `id, pickup_request_id FK, length_mm INT, width_mm INT, height_mm INT, weight_kg DECIMAL(10,2), handling_class VARCHAR(20), qr_token VARCHAR(64) UNIQUE`
- [ ] `qr_token` được sinh ngay khi package được tạo (không cần qua Flow 1 import)
- [ ] Sau khi pickup approved → Driver scan QR để xác nhận xếp lên xe

## Files cần tạo
- `src/main/resources/db/migration/V{next}__pickup_request_package.sql`
- `entity/PickupRequest.java`
- `entity/PickupPackage.java`
- `constant/PickupStatus.java`
