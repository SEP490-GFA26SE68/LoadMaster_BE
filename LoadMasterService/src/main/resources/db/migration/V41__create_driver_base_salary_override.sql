-- Per-driver base salary overrides.
-- When a driver has an active override, it takes precedence over company_payroll_rates.base_salary_vnd
-- during payroll calculation. Only the latest effective override on or before the period start is used.
CREATE TABLE IF NOT EXISTS driver_base_salary_overrides (
    id               BIGSERIAL PRIMARY KEY,
    company_id       BIGINT  NOT NULL REFERENCES companies(id),
    driver_user_id   BIGINT  NOT NULL REFERENCES users(id),
    effective_from   DATE    NOT NULL,
    base_salary_vnd  NUMERIC(15, 2) NOT NULL CHECK (base_salary_vnd >= 0),
    created_at       VARCHAR(19) NOT NULL
                     DEFAULT to_char(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYYY-MM-DD HH24:MI:SS')
                     CHECK (created_at ~ '^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01]) ([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]$'),
    CONSTRAINT uk_driver_base_salary_effective UNIQUE (driver_user_id, effective_from)
);

CREATE INDEX IF NOT EXISTS idx_driver_base_salary_overrides_driver_effective
    ON driver_base_salary_overrides(driver_user_id, effective_from DESC);
