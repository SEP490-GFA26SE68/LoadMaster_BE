# S2-14 · Trip CRUD

| Field | Value |
|-------|-------|
| **Sprint** | 2 |
| **Module** | Trip & Stop |
| **Priority** | 🔴 Must |
| **Label** | `BE` |
| **PRD Ref** | FR-TRIP-01 |
| **Status** | 🔲 Todo |

## Mô tả
CRUD Trip — chuyến đi gắn với xe.

## Acceptance Criteria
- [ ] `TripRepository` — `findByCompanyId()`, `findByVehicleId()`, `findByStatus()`
- [ ] `TripService` — create (auto-gen `tripCode`), update, getById, getAll (paging + filter)
- [ ] `TripController` — `GET/POST/PUT /api/trips`
- [ ] Auto-gen `tripCode` format: `TRIP-{yyyyMMdd}-{seq}`
- [ ] Gắn `vehicleId`, validate vehicle thuộc company
- [ ] Persist đúng: `companyId`, `tripCode`, `vehicleId`, `createdByDispatcherId`, `status` (mặc định `DRAFT`)
- [ ] Validate dispatcher tồn tại, có role phù hợp và thuộc cùng company
- [ ] Status là `VARCHAR(30)`; flow nghiệp vụ phải bao gồm điểm bắt đầu `DRAFT` và không thêm cột ngoài schema

## Files cần tạo
- `repository/TripRepository.java`
- `service/TripService.java`
- `controller/TripController.java`
- `dto/request/TripRequest.java`
- `dto/response/TripResponse.java`

## Dependencies
- S2-02 (Vehicle CRUD)
