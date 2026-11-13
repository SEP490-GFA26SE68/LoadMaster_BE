-- =============================================================================
-- V23: Ràng buộc định dạng Mã số thuế (tax_code) chỉ gồm chữ số (10 hoặc 13 số)
--      và Số điện thoại (phone_number) không chứa chữ cái trong Database
-- =============================================================================

-- 1. Bảng guest_registration_sessions
ALTER TABLE public.guest_registration_sessions
    DROP CONSTRAINT IF EXISTS chk_guest_reg_tax_code_format;

ALTER TABLE public.guest_registration_sessions
    ADD CONSTRAINT chk_guest_reg_tax_code_format
    CHECK (tax_code ~ '^([0-9]{10}|[0-9]{10}-[0-9]{3}|[0-9]{13})$') NOT VALID;

ALTER TABLE public.guest_registration_sessions
    DROP CONSTRAINT IF EXISTS chk_guest_reg_phone_format;

ALTER TABLE public.guest_registration_sessions
    ADD CONSTRAINT chk_guest_reg_phone_format
    CHECK (phone_number IS NULL OR phone_number ~ '^\+?[0-9\s.-]{8,20}$') NOT VALID;

-- 2. Bảng companies
ALTER TABLE public.companies
    DROP CONSTRAINT IF EXISTS chk_companies_tax_code_format;

ALTER TABLE public.companies
    ADD CONSTRAINT chk_companies_tax_code_format
    CHECK (tax_code IS NULL OR tax_code ~ '^([0-9]{10}|[0-9]{10}-[0-9]{3}|[0-9]{13})$') NOT VALID;

-- 3. Bảng company_agreements
ALTER TABLE public.company_agreements
    DROP CONSTRAINT IF EXISTS chk_company_agreements_tax_code_format;

ALTER TABLE public.company_agreements
    ADD CONSTRAINT chk_company_agreements_tax_code_format
    CHECK (tax_code IS NULL OR tax_code ~ '^([0-9]{10}|[0-9]{10}-[0-9]{3}|[0-9]{13})$') NOT VALID;

