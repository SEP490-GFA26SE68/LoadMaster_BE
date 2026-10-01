# S3b-01 · Entity Migration — Package QR & Handling Class

| Field | Value |
|-------|-------|
| **Sprint** | 3b |
| **Module** | Entity / Migration |
| **Priority** | 🔴 Must |
| **Label** | `BE` `DB` |
| **PRD Ref** | FLOW-1 |
| **Status** | 🔲 Todo |

## Mô tả
Thêm các cột mới vào bảng `packages` để hỗ trợ QR identification và cargo handling classification.

## Acceptance Criteria
- [ ] Migration thêm cột `qr_token VARCHAR(64) UNIQUE NOT NULL` vào `packages`
- [ ] Migration thêm cột `package_code VARCHAR(100)` (mã của công ty khách hàng, có thể null)
- [ ] Migration thêm cột `handling_class VARCHAR(20) NOT NULL DEFAULT ''STANDARD''`
- [ ] `handling_class` chỉ chấp nhận: `STANDARD`, `FRAGILE`, `REFRIGERATED`, `HAZARDOUS`, `HIGH_VALUE`
- [ ] `qr_token` được sinh tự động khi insert (UUID), không bao giờ thay đổi dù field khác thay đổi
- [ ] Index trên `qr_token` để lookup nhanh
- [ ] Java entity `Package` cập nhật thêm 3 field tương ứng với annotation đúng

## Lưu ý nghiệp vụ
- `qr_token` phải stable: QR đã in không bao giờ lỗi dù sau này thêm field mới
- `package_code` là mã nội bộ của company, không phải barcode hệ thống

## Files cần sửa/tạo
- `src/main/resources/db/migration/V{next}__add_qr_token_handling_class.sql`
- `entity/Package.java` — thêm 3 field mới
