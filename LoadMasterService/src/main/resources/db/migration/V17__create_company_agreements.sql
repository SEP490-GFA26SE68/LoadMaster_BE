-- V12: Bảng quản lý thỏa thuận sử dụng dịch vụ của doanh nghiệp (Trial Terms / Contracts)
CREATE TABLE IF NOT EXISTS company_agreements (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    user_id BIGINT REFERENCES users(id) ON DELETE SET NULL,
    agreement_type VARCHAR(50) NOT NULL DEFAULT 'TRIAL_TERMS',
    agreement_code VARCHAR(100) UNIQUE NOT NULL,
    title VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    deadline_at TIMESTAMP NOT NULL,
    signed_at TIMESTAMP,
    signer_name VARCHAR(150),
    signer_email VARCHAR(150),
    signer_phone VARCHAR(50),
    company_name VARCHAR(255),
    company_code VARCHAR(50),
    tax_code VARCHAR(50),
    ip_address VARCHAR(100),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_company_agreements_company_id ON company_agreements(company_id);
CREATE INDEX IF NOT EXISTS idx_company_agreements_status_deadline ON company_agreements(status, deadline_at);
