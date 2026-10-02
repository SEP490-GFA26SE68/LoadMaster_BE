# S3b-03 · PackageImportValidator

| Field | Value |
|-------|-------|
| **Sprint** | 3b |
| **Module** | Import |
| **Priority** | 🔴 Must |
| **Label** | `BE` |
| **PRD Ref** | FLOW-1 |
| **Depends on** | S3b-02 |
| **Status** | 🔲 Todo |

## Mô tả
Validate từng row của batch import, thu thập tất cả lỗi (không dừng ở lỗi đầu tiên), trả về preview.

## Acceptance Criteria
- [ ] Validate `lengthMm`, `widthMm`, `heightMm` > 0
- [ ] Validate `weightKg` > 0
- [ ] Validate `handlingClass` thuộc: `STANDARD`, `FRAGILE`, `REFRIGERATED`, `HAZARDOUS`, `HIGH_VALUE`
- [ ] Phát hiện duplicate `packageCode` trong cùng batch → báo row nào trùng
- [ ] Phát hiện `packageCode` đã tồn tại trong DB (theo company) → cảnh báo (warning, không reject)
- [ ] `destination` không được rỗng
- [ ] Trả về `ImportPreviewResult` chứa: valid rows, error rows (kèm row index + message), warning rows
- [ ] Nếu > 0 error → không persist, trả về preview để FE hiển thị
- [ ] Stateless: không tạo entity `ImportJob` hay `ImportRowError`

## DTO
```java
public record ImportPreviewResult(
    int totalRows,
    int validCount,
    List<RowError> errors,
    List<RowWarning> warnings
) {}
```

## Files cần tạo
- `service/import_/PackageImportValidator.java`
- `dto/import_/ImportPreviewResult.java`
