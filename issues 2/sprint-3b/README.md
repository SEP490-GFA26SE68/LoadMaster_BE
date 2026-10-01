# Sprint 3b — Flow 1: Package Registration & QR Handoff

> 7 issues. Mục tiêu: upload hàng loạt CSV/Excel, sinh QR ổn định, scan QR tra cứu package.
> Entity chuẩn: `Package` (không phải `CargoPackage`). QR token = UUID stable.

| Issue | Phạm vi |
|---|---|
| S3b-01 | Entity migration: thêm `qr_token`, `package_code`, `handling_class` vào `packages` |
| S3b-02 | `CsvExcelParserService` — parse file thành List rows (tái dùng từ S2-09) |
| S3b-03 | `PackageImportValidator` — validate từng row: dimension, weight, handling_class, duplicate |
| S3b-04 | `QrCodeService` — generate QR image (ZXing), lookup by token |
| S3b-05 | `PackageImportService` — orchestrate parse → validate → persist → generate QR |
| S3b-06 | `PackageImportController` — upload endpoint + scan endpoint |
| S3b-07 | PDF label export — iText: QR + package summary |
