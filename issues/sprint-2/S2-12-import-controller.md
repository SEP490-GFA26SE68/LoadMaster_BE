# S2-12 · ImportController + DTO

| Field | Value |
|-------|-------|
| **Sprint** | 2 |
| **Module** | Import |
| **Priority** | 🔴 Must |
| **Label** | `BE` |
| **PRD Ref** | FR-ORD-04 (SD05) |
| **Status** | 🔲 Todo |

## Mô tả
Controller cho import shipment — upload/preview và confirm stateless theo schema v3.4.

## Acceptance Criteria
- [ ] `POST /api/imports/shipments/preview` — multipart upload → preview response
- [ ] `POST /api/imports/shipments/confirm` — body chứa valid rows hoặc preview token → tạo `Order` + `Package`
- [ ] Không cung cấp endpoint trạng thái theo `jobId` vì schema không lưu import job

## Request/Response
```json
// POST /api/imports/shipments (multipart/form-data)
// file: shipment.csv

// Response 200 (preview)
{
  "totalRows": 50,
  "validRows": 47,
  "invalidRows": 3,
  "errors": [
    { "row": 12, "column": "weight", "message": "Giá trị phải là số dương" },
    { "row": 25, "column": "packageCode", "message": "Mã trùng lặp" }
  ]
}
```

## Files cần tạo
- `controller/ImportController.java`
- `dto/response/ImportPreviewResponse.java`
- `dto/response/ImportRowErrorResponse.java` (DTO thuần, không phải entity)

## Dependencies
- S2-11 (ImportService)
