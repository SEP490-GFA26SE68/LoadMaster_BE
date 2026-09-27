# S2-09 · CsvExcelParser Service

| Field | Value |
|-------|-------|
| **Sprint** | 2 |
| **Module** | Import |
| **Priority** | 🔴 Must |
| **Label** | `BE` |
| **PRD Ref** | FR-ORD-04 (SD05) |
| **Status** | 🔲 Todo |

## Mô tả
Service parse file CSV (OpenCSV) và Excel (Apache POI) thành danh sách rows.

## Acceptance Criteria
- [ ] Nhận `MultipartFile` → detect loại file (`.csv` hoặc `.xlsx`)
- [ ] Nếu file type không hỗ trợ → throw `UnsupportedFileTypeException`
- [ ] Nếu file rỗng → throw `EmptyFileException`
- [ ] Parse CSV dùng OpenCSV → `List<Map<String, String>>`
- [ ] Parse Excel dùng Apache POI → `List<Map<String, String>>`
- [ ] Hàng đầu tiên = header

## Dependencies (pom.xml)
```xml
<dependency>
    <groupId>com.opencsv</groupId>
    <artifactId>opencsv</artifactId>
    <version>5.9</version>
</dependency>
<dependency>
    <groupId>org.apache.poi</groupId>
    <artifactId>poi-ooxml</artifactId>
    <version>5.2.5</version>
</dependency>
```

## Files cần tạo
- `service/import_/CsvExcelParser.java`
