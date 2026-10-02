# S8-04 · CreditService

| Field | Value |
|-------|-------|
| **Sprint** | 8 |
| **Module** | Credit |
| **Priority** | 🔴 Must |
| **Label** | `BE` |
| **PRD Ref** | FLOW-8 |
| **Depends on** | S8-01 |
| **Status** | 🔲 Todo |

## Acceptance Criteria
- [ ] `GET /api/credits/balance` — balance hiện tại của company
- [ ] `GET /api/credits/transactions` — lịch sử credit (phân trang)
- [ ] `deductCredit(Long companyId, String reference)` — trừ 1 credit; ném `InsufficientCreditsException` nếu balance = 0
  - Sử dụng `SELECT ... FOR UPDATE` hoặc optimistic locking để tránh race condition
  - Tạo `CreditTransaction { type=USAGE, amount=-1, reference=job_uuid }`
- [ ] `refundCredit(Long companyId, String reference)` — hoàn 1 credit khi job FAILED
  - Chỉ refund nếu transaction chưa `refunded`
  - Set `refunded=true` + tạo `CreditTransaction { type=REFUND, amount=+1 }`
  - (Fix B4 trong PRD)
- [ ] `grantMonthlyCredits(Long companyId, int amount)` — cấp credit hàng tháng
- [ ] `POST /api/credits/topup` — CompanyAdmin mua thêm credit → tạo payment URL
- [ ] ULTIMATE subscription: deductCredit luôn pass (không thực sự trừ)

## Files cần tạo
- `service/credit/CreditService.java`
- `repository/CreditAccountRepository.java`
- `repository/CreditTransactionRepository.java`
- `exception/InsufficientCreditsException.java`
