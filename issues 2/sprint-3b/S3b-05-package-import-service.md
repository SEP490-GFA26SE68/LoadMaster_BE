# S3b-05 · PackageImportService (orchestrator)

| Field | Value |
|-------|-------|
| **Sprint** | 3b |
| **Module** | Import |
| **Priority** | 🔴 Must |
| **Label** | `BE` |
| **PRD Ref** | FLOW-1 |
| **Depends on** | S3b-02, S3b-03, S3b-04 |
| **Status** | 🔲 Todo |

## Mô tả
Orchestrate toàn bộ flow: parse → validate → persist packages → generate QR tokens.

## Acceptance Criteria
- [ ] `preview(MultipartFile file, Long companyId)` → `ImportPreviewResult` (stateless, không persist)
- [ ] `confirm(MultipartFile file, Long companyId, Long createdByUserId)` → `ImportConfirmResult`
  - Parse + validate (nếu có lỗi → throw `ImportValidationException`)
  - Persist từng valid row thành `Package` entity (batch insert)
  - Sinh `qr_token` = UUID cho mỗi package
  - Ghi `AuditLog` action_type=`PACKAGE_IMPORT_CONFIRMED`, entityName=`PACKAGE`, thông tin batch
- [ ] Transaction: nếu bất kỳ row nào persist lỗi → rollback toàn bộ batch
- [ ] Trả về `ImportConfirmResult` { totalImported, packageIds[] }

## Files cần tạo
- `service/import_/PackageImportService.java`
- `dto/import_/ImportConfirmResult.java`
