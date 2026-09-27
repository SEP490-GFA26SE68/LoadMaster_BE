# S4-06 · Warehouse: Cập nhật LoadingExecution + Seal

| Field | Value |
|-------|-------|
| **Sprint** | 4 |
| **Module** | Warehouse Loading |
| **Priority** | 🔴 Must |
| **Label** | `BE` |
| **PRD Ref** | FR-WH-03, FR-WH-04, FR-WH-05 |
| **Status** | 🔄 Needs schema revalidation |

## Acceptance Criteria

### Complete Loading
- [x] `POST /api/warehouse/executions/{id}/complete` — body `{ "sealNumber": "SEAL-001" }`
- [x] Validate execution thuộc worker/company hiện tại và đang `IN_PROGRESS`
- [x] Set `sealNumber`, `status = COMPLETED`; cập nhật `Trip.status` theo flow nghiệp vụ
- [x] AuditLog `LOADING_COMPLETED`
- [x] Không giả định có per-placement confirmation/deviation hoặc các field `totalDeviations`, `startedAt`, `completedAt`

## Files đã tạo / cập nhật
- [x] `dto/response/warehouse/PlacementStepResponse.java`
- [x] `dto/request/warehouse/CompleteLoadingRequest.java`
- [x] `dto/response/warehouse/CompleteLoadingResponse.java`
- [x] `controller/warehouse/WarehouseController.java`
- [x] `service/warehouse/WarehouseService.java`
- [x] `service/warehouse/Impl/WarehouseServiceImpl.java`
- [x] `constant/trip/TripStatus.java` (`READY_FOR_DELIVERY`)
- [x] `exception/ErrorCode.java`
- [x] `exception/GlobalExceptionHandler.java`
- [x] `test/service/warehouse/WarehouseServiceTest.java`
- [x] `test/controller/warehouse/WarehouseControllerTest.java`

## Dependencies
- S4-05 (Tasks + Start)
