# S2-01 · VehicleType CRUD

| Field | Value |
|-------|-------|
| **Sprint** | 2 |
| **Module** | Vehicle |
| **Priority** | 🔴 Must |
| **Label** | `BE` |
| **PRD Ref** | FR-VEH-01 |
| **Status** | 🔲 Todo |

## Mô tả
CRUD VehicleType — định nghĩa loại xe với kích thước lòng thùng và tải trọng.

## Acceptance Criteria
- [ ] `VehicleTypeRepository` — JPA Repository
- [ ] `VehicleTypeService` — create, update, getById, getAll (paging), delete
- [ ] `VehicleTypeController` — `GET/POST/PUT/DELETE /api/vehicle-types`
- [ ] Validate: `innerLength`, `innerWidth`, `innerHeight > 0`; `maxPayloadKg > 0`
- [ ] Persist đúng các field: `companyId`, `name`, `innerLength`, `innerWidth`, `innerHeight`, `maxPayloadKg`; không có `doorWidth`/`doorHeight`
- [ ] Filter theo `companyId` — Company Admin chỉ xem loại xe của mình
- [ ] `@PreAuthorize("hasAuthority('VEHICLE_TYPE_MANAGE')")`

## Request/Response
```json
// Request
{ "name": "Xe tải 5 tấn", "innerLength": 4200, "innerWidth": 2000, "innerHeight": 1800, "maxPayloadKg": 5000.00 }

// Response
{ "id": 1, "name": "Xe tải 5 tấn", "innerLength": 4200, "innerWidth": 2000, "innerHeight": 1800, "maxPayloadKg": 5000.00, "companyId": 1 }
```

## Files cần tạo
- `repository/VehicleTypeRepository.java`
- `service/VehicleTypeService.java`
- `controller/VehicleTypeController.java`
- `dto/request/VehicleTypeRequest.java`
- `dto/response/VehicleTypeResponse.java`
