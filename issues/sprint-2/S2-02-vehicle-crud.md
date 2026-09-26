# S2-02 · Vehicle CRUD

| Field | Value |
|-------|-------|
| **Sprint** | 2 |
| **Module** | Vehicle |
| **Priority** | 🔴 Must |
| **Label** | `BE` |
| **PRD Ref** | FR-VEH-02 |
| **Status** | 🔲 Todo |

## Mô tả
CRUD Vehicle — xe cụ thể gắn với loại xe, biển số, giới hạn tải trục.

## Acceptance Criteria
- [ ] `VehicleRepository` — `findByLicensePlate()`, `findByCompanyId()`
- [ ] `VehicleService` — create, update, getById, getAll (paging + filter), delete
- [ ] `VehicleController` — `GET/POST/PUT/DELETE /api/vehicles`
- [ ] Validate: unique `licensePlate`; `vehicleTypeId` tồn tại và thuộc cùng `companyId`; hai tải trục > 0
- [ ] Filter: `companyId`, `vehicleTypeId`, `driverUserId` (schema không có field `status`)
- [ ] Persist đúng: `companyId`, `vehicleTypeId`, `driverUserId` nullable/unique, `licensePlate`, `frontAxleLimitKg`, `rearAxleLimitKg`

## Request/Response
```json
// Request
{ "vehicleTypeId": 1, "licensePlate": "51C-123.45", "frontAxleLimitKg": 2500.00, "rearAxleLimitKg": 5000.00 }
```

## Files cần tạo
- `repository/VehicleRepository.java`
- `service/VehicleService.java`
- `controller/VehicleController.java`
- `dto/request/VehicleRequest.java`
- `dto/response/VehicleResponse.java`

## Dependencies
- S2-01 (VehicleType phải có trước)
