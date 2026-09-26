# S4-04 · Loại bỏ PlacementConfirmation + Deviation khỏi persistence

| Field | Value |
|-------|-------|
| **Sprint** | 4 |
| **Module** | Warehouse |
| **Priority** | 🔴 Must |
| **Label** | `BE` |
| **Status** | 🔲 Todo — schema cleanup |

`refactor_entity.md` v3.4 không có hai entity/bảng này. Tiến độ kho được quản lý ở mức kế hoạch bằng `LoadingExecution`.

## Acceptance Criteria
- [ ] Không tạo/duy trì entity, repository hoặc migration cho `PlacementConfirmation` và `Deviation`
- [ ] Không tham chiếu `totalDeviations`, `startedAt`, `completedAt` trên `LoadingExecution`
- [ ] Các thao tác nghiệp vụ được audit bằng `AuditLog(action, entityName, entityId, userId, ipAddress)` khi cần
- [ ] Nếu cần confirmation/deviation chi tiết trong tương lai, phải cập nhật data dictionary trước khi mở lại feature
