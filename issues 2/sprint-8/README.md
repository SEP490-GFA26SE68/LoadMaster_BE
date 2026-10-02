# Sprint 8 — Flow 8: Subscription, Credit & Payment

> 8 issues. SaaS model: BASIC/PRO/ULTIMATE tier, credit system, VNPay/Stripe payment.
> FIX B4: Credit refund khi job FAILED. FIX B6: Align với schema Sprint 5 cũ.

| Issue | Phạm vi |
|---|---|
| S8-01 | Entity migration: SubscriptionPlan nâng cấp + Subscription + CreditAccount + CreditTransaction + PaymentTransaction |
| S8-02 | SubscriptionPlanService — CRUD cho SystemManager |
| S8-03 | SubscriptionService — CompanyAdmin mua/quản lý subscription |
| S8-04 | CreditService — balance, deduct, refund, monthly grant |
| S8-05 | VNPayIntegration — tích hợp thanh toán |
| S8-06 | PaymentWebhookController — nhận callback payment, idempotent |
| S8-07 | SubscriptionController — public APIs |
| S8-08 | CreditGuard — middleware kiểm tra credit trước optimization |
