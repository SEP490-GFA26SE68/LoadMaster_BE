# Sprint 5 — Monetization, Support & QA

> 8 issues hiện có, tuân theo billing/support entities trong schema v3.4.

| Issue | Phạm vi schema |
|---|---|
| S5-01 | `SubscriptionPlan(code, name, priceVnd, maxVehicles)` |
| S5-02 | `Subscription(companyId, planId, status, startDate, endDate)` + vehicle quota |
| S5-03 | `Invoice` và `PaymentTransaction` với số tiền VNĐ |
| S5-04 | `SupportTicket` với description/requester/assignee/status |
| S5-05 | AuditLog viewer dùng field `action` |
| S5-06 | Seed Role, Permission, RolePermission và User bằng email |
| S5-07 | Integration tests cho các flow đã chuẩn hóa |
| S5-08 | `ContactByGuest`: tiếp nhận guest và assign supporter nullable |

Không dùng `billingCycle`, `maxMonthlyJobs`, plan `active`, hoặc ticket `category`/`priority`/`subject`/`jobId` vì không có trong schema.
