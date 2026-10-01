# S3b-06 · PackageImportController

| Field | Value |
|-------|-------|
| **Sprint** | 3b |
| **Module** | Import |
| **Priority** | 🔴 Must |
| **Label** | `BE` |
| **PRD Ref** | FLOW-1 |
| **Depends on** | S3b-05 |
| **Status** | 🔲 Todo |

## Mô tả
Controller expose API upload file, preview, confirm và scan QR.

## Acceptance Criteria
- [ ] `POST /api/packages/import/preview` — multipart file → trả `ImportPreviewResult` (không persist)
- [ ] `POST /api/packages/import/confirm` — multipart file → persist + trả `ImportConfirmResult`
- [ ] `GET /api/packages/{id}/qr` — trả PNG image (`MediaType.IMAGE_PNG_VALUE`)
- [ ] `GET /api/packages/scan/{qrToken}` — tra cứu bằng QR token → `PackageDetailResponse`
- [ ] `GET /api/packages/import/template` — trả file Excel mẫu (`packages_template.xlsx`)
- [ ] `GET /api/packages/{id}` — lấy thông tin chi tiết package
- [ ] `@PreAuthorize("hasAuthority(''PACKAGE_MANAGE'')")` trên import endpoints
- [ ] `@PreAuthorize("hasAnyAuthority(''PACKAGE_MANAGE'',''WAREHOUSE_WORKER'')")` trên scan endpoint
- [ ] Request body validate: file type, file size (max 10MB)

## Files cần tạo/sửa
- `controller/package_/PackageImportController.java`
- `dto/response/PackageDetailResponse.java`
