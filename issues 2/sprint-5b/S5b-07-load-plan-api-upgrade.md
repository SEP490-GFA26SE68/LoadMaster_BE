# S5b-07 · LoadPlan API Response Upgrade

| Field | Value |
|-------|-------|
| **Sprint** | 5b |
| **Module** | Optimize / API |
| **Priority** | 🟡 Should |
| **Label** | `PYTHON` `BE` |
| **PRD Ref** | FLOW-3, FLOW-4 |
| **Depends on** | S5b-01 |
| **Status** | 🔲 Todo |

## Mô tả
Cập nhật `LoadPlanResponse` để trả thêm COG, axle load, rehandling metrics — phục vụ Flow 4 (Dispatcher review).

## Acceptance Criteria
- [ ] `LoadPlanResponse` thêm fields: `cogX`, `cogY`, `cogZ`, `frontAxleLoad`, `rearAxleLoad`, `rehandlingCount`
- [ ] `PackagePlacementResponse` thêm field: `stopZoneId`, `stopZoneName` (tên điểm giao)
- [ ] `GET /api/v1/load-plans/compare?planId1=&planId2=` trả thêm: COG diff, axle load diff, rehandling diff, deadline feasibility
- [ ] Deadline feasibility: so sánh `planned_arrival` từng stop với deadline của stop đó
- [ ] Spring Boot `LoadPlanResponse` (Java) cũng cập nhật tương ứng

## Files cần sửa
- OptimizeService: `app/dto/response/load_plan_response.py`
- LoadMasterService: `dto/response/LoadPlanResponse.java` (nếu có Java version)
- OptimizeService: `app/controller/optimization/load_plan_router.py`
