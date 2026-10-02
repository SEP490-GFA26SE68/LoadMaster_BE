# S8-03 · SubscriptionService — CompanyAdmin quản lý subscription

| Field | Value |
|-------|-------|
| **Sprint** | 8 |
| **Module** | Subscription |
| **Priority** | 🔴 Must |
| **Label** | `BE` |
| **PRD Ref** | FLOW-8 |
| **Depends on** | S8-01, S8-04 |
| **Status** | 🔲 Todo |

## Acceptance Criteria
- [ ] `GET /api/subscription/current` — subscription hiện tại của company
- [ ] `POST /api/subscription/subscribe` — CompanyAdmin đăng ký plan
  - Validate: company chưa có ACTIVE subscription (hoặc đang EXPIRED)
  - Tạo `PaymentTransaction` status=PENDING
  - Redirect/trả URL payment gateway (VNPay hoặc Stripe)
  - Sau payment success → activate subscription + grant monthly credits
- [ ] `POST /api/subscription/cancel` — hủy subscription (expires tại ngày hết hạn, không hoàn tiền)
- [ ] Auto-renewal: Cron job hàng ngày kiểm tra subscription sắp hết hạn → tự tạo payment
- [ ] Khi subscribe → tự tạo `CreditAccount` nếu chưa có + grant `monthly_credits` lần đầu
- [ ] `@PreAuthorize("hasAuthority(''COMPANY_ADMIN'')")`

## Files cần tạo
- `service/subscription/SubscriptionService.java`
- `service/subscription/SubscriptionRenewalJob.java` (@Scheduled)
