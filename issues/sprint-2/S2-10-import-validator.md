# S2-10 · ImportValidator Service

| Field | Value |
|-------|-------|
| **Sprint** | 2 |
| **Module** | Import |
| **Priority** | 🔴 Must |
| **Label** | `BE` |
| **PRD Ref** | FR-ORD-04 (SD05) |
| **Status** | 🔲 Todo |

## Mô tả
Validate dữ liệu import: kiểm tra headers và từng dòng.

## Acceptance Criteria
- [ ] `validateColumns(headers)` — kiểm tra đủ cột để tạo `Order` và `Package`: `orderCode`, `customerId`, `packageTypeId`, `trackingBarcode`, `actualLength`, `actualWeightKg`
- [ ] Nếu thiếu cột → trả `MissingColumnError` + danh sách cột thiếu
- [ ] `validateRow(row)` — cho từng dòng:
  - [ ] Numeric fields phải là số hợp lệ
  - [ ] `actualLength > 0`, `actualWeightKg > 0`
  - [ ] `trackingBarcode` không trùng trong file hoặc database
  - [ ] `customerId` và `packageTypeId` tồn tại, thuộc company hiện tại
  - [ ] Không nhận các cột ngoài schema như orientation/rotation hoặc kích thước thực tế width/height
- [ ] Trả `List<ValidRow>` + `List<InvalidRow>` kèm `errorReason`

## Files cần tạo
- `service/import_/ImportValidator.java`

## Dependencies
- S2-09 (CsvExcelParser)
