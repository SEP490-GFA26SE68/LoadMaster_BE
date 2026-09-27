# S2-05 · PackageType CRUD

| Field | Value |
|-------|-------|
| **Sprint** | 2 |
| **Module** | Order & Package |
| **Priority** | 🔴 Must |
| **Label** | `BE` |
| **PRD Ref** | FR-ORD-02 |
| **Status** | 🔲 Todo |

## Mô tả
CRUD PackageType — định nghĩa loại kiện hàng với kích thước, trọng lượng, khả năng chịu tải chồng và tính dễ vỡ.

## Acceptance Criteria
- [ ] `PackageTypeRepository` — `findByCompanyId()`
- [ ] `PackageTypeService` — CRUD + validation
- [ ] `PackageTypeController` — `GET/POST/PUT/DELETE /api/package-types`
- [ ] Validate: `length/width/height > 0`, `weightKg > 0`, `maxStackingWeightKg >= 0`
- [ ] Fields: `companyId`, `name`, `length`, `width`, `height`, `weightKg`, `maxStackingWeightKg`, `isFragile`
- [ ] Không dùng `typeCode` hoặc `allowRotateX/Y/Z` vì không có trong schema v3.4

## Request/Response
```json
// Request
{ "name": "Thùng nhỏ", "length": 400, "width": 300, "height": 300, "weightKg": 12.50, "maxStackingWeightKg": 50.00, "isFragile": false }
```

## Files cần tạo
- `repository/PackageTypeRepository.java`
- `service/PackageTypeService.java`
- `controller/PackageTypeController.java`
- `dto/request/PackageTypeRequest.java`
- `dto/response/PackageTypeResponse.java`
