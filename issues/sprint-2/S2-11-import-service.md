# S2-11 · ImportService — Orchestration

| Field | Value |
|-------|-------|
| **Sprint** | 2 |
| **Module** | Import |
| **Priority** | 🔴 Must |
| **Label** | `BE` |
| **PRD Ref** | FR-ORD-04 (SD05) |
| **Status** | 🔲 Todo |

## Mô tả
Service điều phối luồng import trong bộ nhớ: parse → validate → preview → confirm. Schema v3.4 không có `ImportJob`/`ImportRowError`, vì vậy không được tạo bảng/entity cho trạng thái import.

## Acceptance Criteria
- [ ] `previewShipment(file)` gọi parser/validator và trả valid/invalid rows, không ghi database
- [ ] `confirmImport(rows)` nhận lại danh sách row đã xác nhận (hoặc signed preview token), validate lần cuối rồi:
  1. INSERT `Order` cho mỗi nhóm đơn
  2. INSERT `Package` cho mỗi row
  3. Tính `orders.total_weight_kg` từ `packages.actual_weight_kg`
- [ ] Toàn bộ confirm chạy trong một transaction; lỗi thì rollback
- [ ] Không tham chiếu `jobId`, `ImportJob` hoặc `ImportRowError`

## Files cần tạo
- `service/import_/ImportService.java`

## Dependencies
- S2-09, S2-10, S2-06 (`Order`), S2-07 (`Package`)
