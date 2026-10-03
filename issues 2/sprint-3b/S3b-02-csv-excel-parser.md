# S3b-02 · CsvExcelParserService (tái sử dụng + mở rộng)

| Field | Value |
|-------|-------|
| **Sprint** | 3b |
| **Module** | Import |
| **Priority** | 🔴 Must |
| **Label** | `BE` |
| **PRD Ref** | FLOW-1 |
| **Depends on** | S3b-01 |
| **Status** | 🔲 Todo |

## Mô tả
Mở rộng `CsvExcelParser` từ S2-09 để parse format package import mới với các cột: `package_code`, `length`, `width`, `height`, `weight`, `handling_class`, `destination`.

## Acceptance Criteria
- [ ] Nhận `MultipartFile` → detect `.csv` hoặc `.xlsx`
- [ ] File không hỗ trợ → `UnsupportedFileTypeException`
- [ ] File rỗng hoặc chỉ có header → `EmptyFileException`
- [ ] File > 10MB → `FileSizeLimitExceededException`
- [ ] Batch > 1000 rows → `BatchSizeLimitExceededException`
- [ ] Parse → `List<PackageImportRow>` DTO với các field: `packageCode`, `lengthMm`, `widthMm`, `heightMm`, `weightKg`, `handlingClass`, `destination`
- [ ] Dòng đầu = header, bỏ qua dòng trắng
- [ ] Trả về cả row index (1-based) để báo lỗi chính xác

## DTO
```java
public record PackageImportRow(
    int rowIndex,
    String packageCode,
    Integer lengthMm,
    Integer widthMm,
    Integer heightMm,
    BigDecimal weightKg,
    String handlingClass,
    String destination
) {}
```

## Files cần tạo
- `service/import_/PackageImportRow.java` (DTO)
- `service/import_/CsvExcelParserService.java` (mở rộng từ S2-09)
