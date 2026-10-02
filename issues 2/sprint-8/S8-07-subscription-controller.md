# S8-07 · SubscriptionController — Public APIs

| Field | Value |
|-------|-------|
| **Sprint** | 8 |
| **Module** | Subscription |
| **Priority** | 🔴 Must |
| **Label** | `BE` |
| **PRD Ref** | FLOW-8 |
| **Depends on** | S8-02, S8-03, S8-04 |
| **Status** | 🔲 Todo |

## Acceptance Criteria
- [ ] `GET /api/subscription/plans` — public (không cần auth)
- [ ] `GET /api/subscription/current` — subscription + credit balance của company hiện tại
- [ ] `POST /api/subscription/subscribe` — CompanyAdmin chọn plan + trigger payment
- [ ] `POST /api/subscription/cancel` — hủy subscription
- [ ] `GET /api/credits/balance` — balance hiện tại
- [ ] `GET /api/credits/transactions` — lịch sử (phân trang)
- [ ] `POST /api/credits/topup` — mua thêm credit → trả payment URL
- [ ] Response `SubscriptionCurrentResponse`: `{ planName, tier, status, expiresAt, creditsBalance, creditsMonthlyGrant, algorithmTier }`

## Files cần tạo
- `controller/subscription/SubscriptionController.java`
- `controller/credit/CreditController.java`
- `dto/response/SubscriptionCurrentResponse.java`
