-- V21: Enhance trial_account_requests and create trial_registration_sessions

-- 1. Create trial_registration_sessions for OTP verification of guest trial sign-ups
CREATE TABLE IF NOT EXISTS trial_registration_sessions (
    id BIGSERIAL PRIMARY KEY,
    token VARCHAR(100) NOT NULL UNIQUE,
    email VARCHAR(150) NOT NULL,
    full_name VARCHAR(150) NOT NULL,
    phone_number VARCHAR(30),
    company_name VARCHAR(200) NOT NULL,
    tax_code VARCHAR(50) NOT NULL,
    company_address TEXT,
    interested_plan_id BIGINT REFERENCES subscription_plans(id),
    note TEXT,
    otp_hash VARCHAR(255),
    otp_expires_at TIMESTAMP,
    otp_resend_allowed_at TIMESTAMP,
    verified BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_trial_reg_token ON trial_registration_sessions(token);
CREATE INDEX IF NOT EXISTS idx_trial_reg_email ON trial_registration_sessions(email);

-- 2. Add columns to trial_account_requests for AI pre-validation and multi-stage approval
ALTER TABLE trial_account_requests ADD COLUMN IF NOT EXISTS company_address TEXT;
ALTER TABLE trial_account_requests ADD COLUMN IF NOT EXISTS ai_precheck_status VARCHAR(30);
ALTER TABLE trial_account_requests ADD COLUMN IF NOT EXISTS ai_precheck_reason TEXT;
ALTER TABLE trial_account_requests ADD COLUMN IF NOT EXISTS handled_by_name VARCHAR(100);
ALTER TABLE trial_account_requests ADD COLUMN IF NOT EXISTS rejection_reason TEXT;
ALTER TABLE trial_account_requests ADD COLUMN IF NOT EXISTS manager_reviewed_by_id BIGINT REFERENCES users(id);

-- Ensure contact_id is nullable for direct guest trial requests
ALTER TABLE trial_account_requests ALTER COLUMN contact_id DROP NOT NULL;

