# S6-01 · Flow 4 Upgrade — Deadline Feasibility + Change Vehicle

| Field | Value |
|-------|-------|
| **Sprint** | 6 |
| **Module** | Load Plan / Approval |
| **Priority** | 🟡 Should |
| **Label** | `BE` `PYTHON` |
| **PRD Ref** | FLOW-4 |
| **Status** | 🔲 Todo |

## Acceptance Criteria
- [ ] Khi Dispatcher approve plan: kiểm tra `DeliveryStop.planned_arrival` ≤ `DeliveryRequirement.deadline` cho từng stop
- [ ] Nếu có stop vi phạm deadline → `ApprovalWarning` (không block, nhưng phải confirm)
- [ ] `POST /api/load-plans/{id}/approve` nhận thêm `force=true` nếu Dispatcher muốn approve dù có deadline warning
- [ ] `POST /api/trips/{id}/change-vehicle` — Dispatcher chọn xe khác
  - Re-validate toàn bộ: dimension fit, payload, axle limits, handling_class compatibility
  - Nếu plan đã approve → cần re-run optimization
  - Ghi AuditLog action_type=`VEHICLE_CHANGED`
- [ ] Compare plan: thêm `deadlineFeasibility: [{stopId, plannedArrival, deadline, status: OK/AT_RISK/MISSED}]`

## Files cần sửa
- OptimizeService: `app/service/optimize/load_plan_service.py`
- LoadMasterService: `service/optimization/Impl/LoadPlanServiceImpl.java`
