# S8-01 · Entity Migration — Subscription & Credit System

| Field | Value |
|-------|-------|
| **Sprint** | 8 |
| **Module** | Entity / Migration |
| **Priority** | 🔴 Must |
| **Label** | `BE` `DB` |
| **PRD Ref** | FLOW-8 |
| **Status** | 🔲 Todo |

## Lưu ý (Fix B6 — Align với Sprint 5)
Sprint 5 cũ đã tạo `subscription_plans` với fields: `code, name, price_vnd, max_vehicles`.
Migration này KHÔNG drop bảng cũ mà ALTER để thêm fields mới.

## Acceptance Criteria — SubscriptionPlan (ALTER, không recreate)
- [ ] `ALTER TABLE subscription_plans ADD COLUMN tier VARCHAR(20) NOT NULL DEFAULT ''BASIC''` — BASIC, PRO, ULTIMATE
- [ ] `ALTER TABLE subscription_plans ADD COLUMN monthly_credits INT` (null = unlimited)
- [ ] `ALTER TABLE subscription_plans ADD COLUMN algorithm_tier VARCHAR(20)` — EP_DBLF, EP_DBLF_GA, EP_DBLF_GA_AI
- [ ] `ALTER TABLE subscription_plans ADD COLUMN features JSON`

## Acceptance Criteria — Subscription
- [ ] Bảng `company_subscriptions`: `id, company_id FK UNIQUE, plan_id FK, status VARCHAR(20), started_at TIMESTAMP, expires_at TIMESTAMP, auto_renew BOOLEAN DEFAULT true`
- [ ] `status`: ACTIVE, EXPIRED, CANCELLED

## Acceptance Criteria — CreditAccount
- [ ] Bảng `credit_accounts`: `id, company_id FK UNIQUE, balance INT NOT NULL DEFAULT 0`
- [ ] Constraint: `balance >= 0`

## Acceptance Criteria — CreditTransaction
- [ ] Bảng `credit_transactions`: `id, credit_account_id FK, amount INT NOT NULL, type VARCHAR(20), reference VARCHAR(100), refunded BOOLEAN DEFAULT false, created_at TIMESTAMP`
- [ ] `type`: MONTHLY_GRANT, PURCHASE, USAGE, REFUND
- [ ] `refunded` flag — Fix B4: khi job FAILED → set refunded=true + tạo REFUND transaction

## Acceptance Criteria — PaymentTransaction
- [ ] Bảng `payment_transactions`: `id, company_id FK, gateway VARCHAR(20), gateway_transaction_id VARCHAR(100) UNIQUE, amount_vnd DECIMAL(15,2), status VARCHAR(20), payload JSON, created_at TIMESTAMP, updated_at TIMESTAMP`
- [ ] UNIQUE constraint trên `gateway_transaction_id` — idempotency

## Files cần tạo
- `src/main/resources/db/migration/V{next}__subscription_credit_system.sql`
- `entity/CompanySubscription.java`
- `entity/CreditAccount.java`
- `entity/CreditTransaction.java`
- `entity/PaymentTransaction.java`
- `constant/SubscriptionTier.java`, `CreditTransactionType.java`
