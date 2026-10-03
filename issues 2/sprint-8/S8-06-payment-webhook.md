# S8-06 · PaymentWebhookController — Idempotent callback handler

| Field | Value |
|-------|-------|
| **Sprint** | 8 |
| **Module** | Payment |
| **Priority** | 🔴 Must |
| **Label** | `BE` |
| **PRD Ref** | FLOW-8 |
| **Depends on** | S8-05, S8-03, S8-04 |
| **Status** | 🔲 Todo |

## Acceptance Criteria
- [ ] `POST /webhook/vnpay` — nhận VNPay IPN callback
  - Verify signature HMAC-SHA512
  - Load `PaymentTransaction` bằng `vnp_TxnRef`
  - Nếu `PaymentTransaction.status` đã là SUCCESS → trả `RspCode=00` luôn (idempotent)
  - Nếu payment success: cập nhật status=SUCCESS → trigger activate subscription hoặc add credits
  - Nếu payment fail: cập nhật status=FAILED
- [ ] `POST /webhook/stripe` — nhận Stripe webhook
  - Verify `Stripe-Signature` header
  - Xử lý event `checkout.session.completed`
  - Idempotent: `gateway_transaction_id` UNIQUE constraint tự bảo vệ
- [ ] Không cần Auth (webhook từ payment gateway)
- [ ] Log toàn bộ raw payload vào `PaymentTransaction.payload` JSON

## Idempotency Implementation
- DB UNIQUE constraint trên `gateway_transaction_id` — INSERT lần 2 sẽ fail → catch và skip

## Files cần tạo
- `controller/payment/PaymentWebhookController.java`
- `service/payment/PaymentCallbackHandler.java`
