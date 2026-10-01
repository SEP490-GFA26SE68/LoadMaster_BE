# S4b-08 · TripStatusService

| Field | Value |
|-------|-------|
| **Sprint** | 4b |
| **Module** | Trip |
| **Priority** | 🔴 Must |
| **Label** | `BE` |
| **PRD Ref** | FLOW-2, FLOW-5, FLOW-6 |
| **Depends on** | S4b-01 |
| **Status** | 🔲 Todo |

## Mô tả
Service quản lý chuyển trạng thái Trip theo đúng state machine. Ngăn chặn transition không hợp lệ.

## Acceptance Criteria
- [ ] State machine: `DRAFT → PLANNED → LOADING → IN_TRANSIT → DELIVERED`; bất kỳ state nào cũng có thể → `CANCELLED`
- [ ] `transitionTo(Long tripId, TripStatus newStatus, Long userId)` — validate + chuyển trạng thái
- [ ] Các transition hợp lệ:
  - `DRAFT → PLANNED`: khi route đã optimize xong
  - `PLANNED → LOADING`: khi LoadingExecution được tạo
  - `LOADING → IN_TRANSIT`: khi loading complete
  - `IN_TRANSIT → DELIVERED`: khi stop cuối cùng complete
- [ ] Transition không hợp lệ → `InvalidTripStatusTransitionException`
- [ ] Ghi `AuditLog` cho mỗi transition
- [ ] Flow 6 (GPS monitoring) chỉ hoạt động khi Trip ở trạng thái `IN_TRANSIT`
- [ ] Flow 7 (pickup) chỉ hoạt động khi Trip ở trạng thái `IN_TRANSIT`

## Files cần tạo
- `service/trip/TripStatusService.java`
- `exception/InvalidTripStatusTransitionException.java`
