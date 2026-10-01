# S8-05 · VNPayIntegration

| Field | Value |
|-------|-------|
| **Sprint** | 8 |
| **Module** | Payment |
| **Priority** | 🔴 Must |
| **Label** | `BE` `INTEGRATION` |
| **PRD Ref** | FLOW-8 |
| **Depends on** | S8-01 |
| **Status** | 🔲 Todo |

## Acceptance Criteria
- [ ] Config VNPay từ `.env`: `VNPAY_TMN_CODE`, `VNPAY_HASH_SECRET`, `VNPAY_URL`, `VNPAY_RETURN_URL`
- [ ] `createPaymentUrl(PaymentTransaction txn, BigDecimal amountVnd)` → VNPay payment URL
  - Build param: `vnp_TmnCode`, `vnp_Amount` (×100), `vnp_OrderInfo`, `vnp_TxnRef` = `txn.id`
  - HMAC-SHA512 signature
- [ ] `verifyCallback(Map<String, String> params)` → `boolean` — verify signature từ VNPay IPN/return
- [ ] Trả về URL cho FE redirect: `{ paymentUrl, transactionId }`
- [ ] Không hardcode amount vào URL, phải từ `PaymentTransaction.amount_vnd`

## Stripe (secondary)
- [ ] Config `STRIPE_SECRET_KEY` từ `.env`
- [ ] `createStripeCheckout(PaymentTransaction txn)` → Stripe Checkout URL
- [ ] Webhook verify bằng `Stripe-Signature` header

## Files cần tạo
- `client/VnPayClient.java`
- `client/StripeClient.java`
- `service/payment/PaymentGatewayService.java` (wrapper cho cả 2)
