# S4-05 · Warehouse: Get Tasks + Start Loading

| Field | Value |
|-------|-------|
| **Sprint** | 4 |
| **Module** | Warehouse Loading |
| **Priority** | 🔴 Must |
| **Label** | `BE` |
| **PRD Ref** | FR-WH-01, FR-WH-02 |
| **Status** | 🔄 Needs schema revalidation |

## Acceptance Criteria
- [x] `GET /api/warehouse/tasks` — Worker xem tasks (Trip có plan APPROVED, chưa loading)
- [x] `POST /api/warehouse/tasks/{loadPlanId}/start` — tạo `LoadingExecution(loadPlanId, workerUserId, status=IN_PROGRESS)`, trả placements theo `loadingSequence`
- [x] Chỉ worker được giao mới thấy task (hoặc tất cả worker cùng company)
- [x] Enforce unique `loadPlanId`; worker tồn tại và dùng FK `worker_user_id` (`ON DELETE RESTRICT`)
- [x] Response: tripCode, vehicleInfo, totalPackages, placements[] (`packageId`, `trackingBarcode`, `posX/Y/Z`, `loadingSequence`)
- [x] Không trả rotation/packageName từ `PackagePlacement` vì các field này không tồn tại

## Files đã tạo / cập nhật
- [x] `controller/warehouse/WarehouseController.java`
- [x] `service/warehouse/WarehouseService.java`
- [x] `service/warehouse/Impl/WarehouseServiceImpl.java`
- [x] `repository/warehouse/LoadingExecutionRepository.java`
- [x] `repository/trip/TripRepository.java`
- [x] `repository/account/UserRepository.java`
- [x] `dto/request/warehouse/StartLoadingRequest.java`
- [x] `dto/response/warehouse/WarehouseTaskResponse.java`
- [x] `dto/response/warehouse/StartLoadingResponse.java`
- [x] `dto/response/warehouse/PlacementStepDto.java`
- [x] `test/service/warehouse/WarehouseServiceTest.java`
- [x] `test/controller/warehouse/WarehouseControllerTest.java`
