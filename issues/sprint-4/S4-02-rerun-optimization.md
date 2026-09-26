# S4-02 · API Rerun Optimization

| Field | Value |
|-------|-------|
| **Sprint** | 4 |
| **Module** | Pin & Rerun |
| **Priority** | 🟡 Should |
| **Label** | `BE` |
| **PRD Ref** | FR-OPT-08 / SD06 |
| **Status** | 🔄 Needs schema revalidation |

## Acceptance Criteria
- [x] `POST /api/load-plans/{id}/rerun` — body `{ objective, timeLimitSec, seed }`
- [x] Load packages có `isPinned = true` từ current plan và dùng placement hiện tại làm input cố định
- [x] Tạo `OptimizationJob` mới cho cùng `tripId`; schema không có `parentPlanId`
- [x] Gửi Engine với pinned package/placement list → Engine fix pinned trước, optimize còn lại
- [x] Lưu `LoadPlan.planVersion = old.planVersion + 1`
- [x] Liên hệ các version qua cùng Trip (`LoadPlan → OptimizationJob → Trip`), không tạo self-FK
