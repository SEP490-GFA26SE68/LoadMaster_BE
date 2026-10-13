-- S8-01: Subscription & Credit System Migration

-- 1. Upgrade subscription_plans (B6 Fix: ALTER, not DROP)
ALTER TABLE subscription_plans ADD COLUMN IF NOT EXISTS tier VARCHAR(20) NOT NULL DEFAULT 'BASIC';
ALTER TABLE subscription_plans ADD COLUMN IF NOT EXISTS monthly_credits INT;
ALTER TABLE subscription_plans ADD COLUMN IF NOT EXISTS algorithm_tier VARCHAR(20) DEFAULT 'EP_DBLF';
ALTER TABLE subscription_plans ADD COLUMN IF NOT EXISTS features TEXT;

-- 2. Company Subscriptions
CREATE TABLE IF NOT EXISTS company_subscriptions (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL UNIQUE REFERENCES companies(id) ON DELETE CASCADE,
    plan_id BIGINT NOT NULL REFERENCES subscription_plans(id),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    started_at TIMESTAMP,
    expires_at TIMESTAMP,
    auto_renew BOOLEAN DEFAULT TRUE
);

-- 3. Credit Accounts
CREATE TABLE IF NOT EXISTS credit_accounts (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL UNIQUE REFERENCES companies(id) ON DELETE CASCADE,
    balance INT NOT NULL DEFAULT 0 CHECK (balance >= 0),
    version BIGINT DEFAULT 0
);

-- 4. Credit Transactions (Fix B4)
CREATE TABLE IF NOT EXISTS credit_transactions (
    id BIGSERIAL PRIMARY KEY,
    credit_account_id BIGINT NOT NULL REFERENCES credit_accounts(id) ON DELETE CASCADE,
    amount INT NOT NULL,
    type VARCHAR(20) NOT NULL,
    reference VARCHAR(100),
    refunded BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_credit_account_id ON credit_transactions(credit_account_id);
CREATE INDEX IF NOT EXISTS idx_credit_reference ON credit_transactions(reference);

-- 5. Payment Transactions (Idempotency)
CREATE TABLE IF NOT EXISTS payment_transactions (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    gateway VARCHAR(20) NOT NULL,
    gateway_transaction_id VARCHAR(100) NOT NULL UNIQUE,
    amount_vnd DECIMAL(15,2) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    payload TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP
);
