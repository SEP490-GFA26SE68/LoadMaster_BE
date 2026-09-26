# S5-02 · Subscription + Vehicle Quota Check

| Field | Value |
|-------|-------|
| **Sprint** | 5 |
| **Module** | Subscription |
| **Priority** | 🟡 Should |
| **Label** | `BE` |
| **PRD Ref** | FR-SUB-02, FR-SUB-04 |
| **Status** | 🔲 Todo |

## Acceptance Criteria
- [ ] `POST /api/subscriptions` — Company Admin đăng ký gói
- [ ] Request/persistence gồm `companyId`, `planId`, `status`, `startDate`, `endDate`
- [ ] Validate company chưa có subscription với `status = ACTIVE`; plan tồn tại
- [ ] Khi tạo xe và trước optimization → kiểm tra số xe của company không vượt `SubscriptionPlan.maxVehicles`
- [ ] Nếu vượt → 403 `QuotaExceededException`
- [ ] Không kiểm tra `maxMonthlyJobs` vì field này không có trong schema v3.4

## Files cần tạo
- `repository/SubscriptionRepository.java`
- `service/SubscriptionService.java`
- `controller/SubscriptionController.java`
