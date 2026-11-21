-- Dispatcher payroll is independent of driver payroll_periods so locking one
-- role does not block the other.

CREATE TABLE IF NOT EXISTS company_dispatcher_payroll_rates (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES companies(id),
    effective_from DATE NOT NULL,
    base_salary_vnd NUMERIC(15, 2) NOT NULL CHECK (base_salary_vnd >= 0),
    overtime_vnd_per_hour NUMERIC(15, 2) NOT NULL CHECK (overtime_vnd_per_hour >= 0),
    overtime_hours_per_day NUMERIC(5, 2) NOT NULL CHECK (overtime_hours_per_day >= 0 AND overtime_hours_per_day <= 24),
    created_at VARCHAR(19) NOT NULL
        DEFAULT to_char(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYYY-MM-DD HH24:MI:SS')
        CHECK (created_at ~ '^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01]) ([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]$'),
    CONSTRAINT uk_company_dispatcher_payroll_rate_effective UNIQUE (company_id, effective_from)
);

CREATE INDEX IF NOT EXISTS idx_company_dispatcher_payroll_rates_company_effective
    ON company_dispatcher_payroll_rates(company_id, effective_from DESC);

CREATE TABLE IF NOT EXISTS dispatcher_payroll_periods (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES companies(id),
    payroll_rate_id BIGINT NOT NULL REFERENCES company_dispatcher_payroll_rates(id),
    period_start DATE NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'LOCKED')),
    locked_at VARCHAR(19)
        CHECK (locked_at IS NULL OR locked_at ~ '^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01]) ([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]$'),
    CONSTRAINT uk_dispatcher_payroll_period_company_start UNIQUE (company_id, period_start)
);

CREATE INDEX IF NOT EXISTS idx_dispatcher_payroll_periods_company_start
    ON dispatcher_payroll_periods(company_id, period_start);

CREATE TABLE IF NOT EXISTS dispatcher_payrolls (
    id BIGSERIAL PRIMARY KEY,
    payroll_period_id BIGINT NOT NULL REFERENCES dispatcher_payroll_periods(id) ON DELETE CASCADE,
    dispatcher_user_id BIGINT NOT NULL REFERENCES users(id),
    base_salary_vnd NUMERIC(15, 0) NOT NULL CHECK (base_salary_vnd >= 0),
    overtime_vnd_per_hour NUMERIC(15, 2) NOT NULL CHECK (overtime_vnd_per_hour >= 0),
    overtime_hours_per_day NUMERIC(5, 2) NOT NULL CHECK (overtime_hours_per_day >= 0 AND overtime_hours_per_day <= 24),
    overtime_days INTEGER NOT NULL DEFAULT 0 CHECK (overtime_days >= 0),
    overtime_amount_vnd NUMERIC(15, 0) NOT NULL DEFAULT 0 CHECK (overtime_amount_vnd >= 0),
    kpi_amount_vnd NUMERIC(15, 0) NOT NULL DEFAULT 0 CHECK (kpi_amount_vnd >= 0),
    total_salary_vnd NUMERIC(15, 0) NOT NULL CHECK (total_salary_vnd >= 0),
    CONSTRAINT uk_dispatcher_payroll_period_dispatcher UNIQUE (payroll_period_id, dispatcher_user_id)
);

CREATE TABLE IF NOT EXISTS dispatcher_base_salary_overrides (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES companies(id),
    dispatcher_user_id BIGINT NOT NULL REFERENCES users(id),
    effective_from DATE NOT NULL,
    base_salary_vnd NUMERIC(15, 2) NOT NULL CHECK (base_salary_vnd >= 0),
    created_at VARCHAR(19) NOT NULL
        DEFAULT to_char(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYYY-MM-DD HH24:MI:SS')
        CHECK (created_at ~ '^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01]) ([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]$'),
    CONSTRAINT uk_dispatcher_base_salary_effective UNIQUE (dispatcher_user_id, effective_from)
);

CREATE INDEX IF NOT EXISTS idx_dispatcher_base_salary_overrides_dispatcher_effective
    ON dispatcher_base_salary_overrides(dispatcher_user_id, effective_from DESC);
