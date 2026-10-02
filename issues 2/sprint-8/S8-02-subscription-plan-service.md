# S8-02 · SubscriptionPlanService — SystemManager CRUD

| Field | Value |
|-------|-------|
| **Sprint** | 8 |
| **Module** | Subscription |
| **Priority** | 🔴 Must |
| **Label** | `BE` |
| **PRD Ref** | FLOW-8 |
| **Depends on** | S8-01 |
| **Status** | 🔲 Todo |

## Acceptance Criteria
- [ ] `GET /api/subscription/plans` — public, danh sách tất cả plans (active)
- [ ] `POST /api/subscription/plans` — SystemManager tạo plan mới
- [ ] `PUT /api/subscription/plans/{id}` — SystemManager cập nhật
- [ ] `DELETE /api/subscription/plans/{id}` — chỉ được xóa nếu không có company đang dùng
- [ ] Validate: `monthly_credits > 0` (hoặc null nếu unlimited); `tier` hợp lệ; `price_vnd >= 0`
- [ ] `@PreAuthorize("hasAuthority(''SYSTEM_MANAGER'')")` trên POST/PUT/DELETE
- [ ] Không được có 2 plan cùng `tier` active cùng lúc (unique tier constraint)

## Files cần sửa/tạo
- `service/subscription/SubscriptionPlanService.java` (mở rộng từ S5-01)
- `controller/subscription/SubscriptionPlanController.java`
