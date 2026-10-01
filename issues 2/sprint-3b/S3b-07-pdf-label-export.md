# S3b-07 · PDF QR Label Export

| Field | Value |
|-------|-------|
| **Sprint** | 3b |
| **Module** | QR / Export |
| **Priority** | 🟡 Should |
| **Label** | `BE` |
| **PRD Ref** | FLOW-1 |
| **Depends on** | S3b-04 |
| **Status** | 🔲 Todo |

## Mô tả
Xuất file PDF chứa QR labels để in. Mỗi label gồm QR code + tóm tắt thông tin package.

## Acceptance Criteria
- [ ] `POST /api/packages/export/labels` — nhận `{ packageIds: [1,2,3,...] }` → trả PDF
- [ ] Mỗi label hiển thị: QR code (2×2cm), package_code, handling_class, kích thước (L×W×H), trọng lượng, destination
- [ ] Layout: 4 labels/trang A4 (2×2 grid)
- [ ] FRAGILE package in thêm icon/text cảnh báo
- [ ] Max 200 packages mỗi request
- [ ] `@PreAuthorize("hasAuthority(''PACKAGE_MANAGE'')")`

## Dependency (pom.xml)
```xml
<dependency>
    <groupId>com.itextpdf</groupId>
    <artifactId>itextpdf</artifactId>
    <version>5.5.13.3</version>
</dependency>
```

## Files cần tạo
- `service/export/PdfLabelExportService.java`
- `controller/package_/PackageExportController.java`
