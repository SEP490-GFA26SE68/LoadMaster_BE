-- V16: Create guest_registration_sessions table for self-registration flow

CREATE TABLE IF NOT EXISTS guest_registration_sessions (
    id BIGSERIAL PRIMARY KEY,
    token VARCHAR(100) NOT NULL,
    email VARCHAR(150) NOT NULL,
    full_name VARCHAR(150) NOT NULL,
    phone_number VARCHAR(20),
    company_name VARCHAR(255) NOT NULL,
    company_code VARCHAR(50) NOT NULL,
    tax_code VARCHAR(50) NOT NULL,
    plan_id BIGINT REFERENCES subscription_plans(id),
    billing_cycle VARCHAR(20),
    status VARCHAR(30) NOT NULL,
    otp_hash VARCHAR(255),
    otp_expires_at TIMESTAMP,
    otp_resend_allowed_at TIMESTAMP,
    policy_accepted_at TIMESTAMP,
    ip_address VARCHAR(100),
    payment_ref VARCHAR(100),
    payment_gateway VARCHAR(20),
    amount_vnd NUMERIC(15, 2),
    created_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_guest_reg_token UNIQUE (token)
);

CREATE INDEX IF NOT EXISTS idx_guest_reg_token ON guest_registration_sessions(token);
CREATE INDEX IF NOT EXISTS idx_guest_reg_email ON guest_registration_sessions(email);
CREATE INDEX IF NOT EXISTS idx_guest_reg_payment_ref ON guest_registration_sessions(payment_ref);

