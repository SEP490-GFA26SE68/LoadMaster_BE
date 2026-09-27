# S4-03 · API Compare Plans

| Field | Value |
|-------|-------|
| **Sprint** | 4 |
| **Module** | Pin & Rerun |
| **Priority** | 🟡 Should |
| **Label** | `BE` |
| **PRD Ref** | FR-OPT-09 |
| **Status** | 🔄 Needs schema revalidation |

## Acceptance Criteria
- [x] `GET /api/load-plans/compare?planId1=X&planId2=Y`
- [x] Trả side-by-side: `volumeUtilizationPercent`; `unplacedCount`/`packedCount` tính từ bảng con; `executionTimeMs` lấy từ job
- [x] `weightUtilization` chỉ được tính runtime từ tổng package weight / `VehicleType.maxPayloadKg`, không persist field mới
- [x] DTO: `PlanComparisonResponse { plan1Metrics, plan2Metrics }`
