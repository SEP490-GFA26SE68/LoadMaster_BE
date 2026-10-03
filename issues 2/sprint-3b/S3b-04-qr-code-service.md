# S3b-04 · QrCodeService

| Field | Value |
|-------|-------|
| **Sprint** | 3b |
| **Module** | QR |
| **Priority** | 🔴 Must |
| **Label** | `BE` |
| **PRD Ref** | FLOW-1, FLOW-5 |
| **Depends on** | S3b-01 |
| **Status** | 🔲 Todo |

## Mô tả
Service tạo và tra cứu QR Code. QR chỉ chứa `qr_token` (UUID), không embed dữ liệu nhạy cảm.

## Acceptance Criteria
- [ ] `generateQrPng(String qrToken)` → `byte[]` PNG image (ZXing, 300×300px)
- [ ] `generateQrSvg(String qrToken)` → `String` SVG (optional)
- [ ] `lookupByToken(String qrToken)` → `Package` entity, ném `PackageNotFoundException` nếu không tồn tại
- [ ] `generateQrToken()` → UUID v4, không bao giờ trùng
- [ ] QR content = chỉ token string (VD: `PKG-a1b2c3d4-...`), không có URL, không có JSON
- [ ] Endpoint `GET /api/packages/scan/{qrToken}` → trả `PackageDetailResponse` kèm handling_class, destination, kích thước
- [ ] Phân quyền: DISPATCHER và WAREHOUSE_WORKER được scan

## Dependency (pom.xml)
```xml
<dependency>
    <groupId>com.google.zxing</groupId>
    <artifactId>core</artifactId>
    <version>3.5.3</version>
</dependency>
<dependency>
    <groupId>com.google.zxing</groupId>
    <artifactId>javase</artifactId>
    <version>3.5.3</version>
</dependency>
```

## Files cần tạo
- `service/qr/QrCodeService.java`
- `controller/package_/PackageScanController.java`
