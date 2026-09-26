# S5-01 · SubscriptionPlan CRUD

| Field | Value |
|-------|-------|
| **Sprint** | 5 |
| **Module** | Subscription |
| **Priority** | 🟡 Should |
| **Label** | `BE` |
| **PRD Ref** | FR-SUB-01 |
| **Status** | 🔲 Todo |

## Acceptance Criteria
- [ ] `GET/POST/PUT /api/subscription-plans` — System Admin only
- [ ] Fields: `code`, `name`, `priceVnd`, `maxVehicles`
- [ ] Validate unique `code`; `priceVnd >= 0`; `maxVehicles > 0`
- [ ] Không thêm `billingCycle`, `maxMonthlyJobs` hoặc `active` vì schema v3.4 không có các cột này

## Files cần tạo
- `repository/SubscriptionPlanRepository.java`
- `service/SubscriptionPlanService.java`
- `controller/SubscriptionPlanController.java`
- `dto/request/SubscriptionPlanRequest.java`
- `dto/response/SubscriptionPlanResponse.java`
