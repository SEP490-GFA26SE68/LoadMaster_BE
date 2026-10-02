# S7-03 · PickupQrService — QR cho pickup package

| Field | Value |
|-------|-------|
| **Sprint** | 7 |
| **Module** | Pickup / QR |
| **Priority** | 🔴 Must |
| **Label** | `BE` |
| **PRD Ref** | FLOW-7 |
| **Depends on** | S7-01, S3b-04 |
| **Status** | 🔲 Todo |

## Mô tả
Sinh QR tạm cho pickup packages ngay tại thời điểm pickup approved. Driver scan để xếp lên xe.

## Acceptance Criteria
- [ ] Khi pickup approved → mỗi `PickupPackage` nhận `qr_token` = UUID mới (tái dùng `QrCodeService`)
- [ ] `GET /api/pickup-packages/{id}/qr` — trả PNG QR image
- [ ] Driver scan → `GET /api/packages/scan/{qrToken}` vẫn hoạt động (unified scan endpoint, tìm trong cả `packages` và `pickup_packages`)
- [ ] Response scan: kèm field `packageSource: ''REGULAR'' | ''PICKUP''` để FE biết đang xử lý loại nào
- [ ] QR của pickup package chứa prefix `PKP-` (ví dụ: `PKP-a1b2c3d4`) để phân biệt với regular QR

## Files cần sửa
- `service/qr/QrCodeService.java` — thêm lookup trong `pickup_packages`
- `controller/pickup/PickupPackageController.java` — thêm QR endpoint
