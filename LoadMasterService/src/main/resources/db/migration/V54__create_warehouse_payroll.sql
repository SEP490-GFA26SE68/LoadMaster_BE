-- Lương nhân viên kho: lương cứng + đơn giá × kg kiện đã xếp lên xe trong tháng + thưởng KPI theo kg.
-- Tách khỏi bảng lương tài xế (payroll_periods) và điều phối để chốt một vai trò không chặn vai trò khác.

CREATE TABLE IF NOT EXISTS company_warehouse_payroll_rates (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES companies(id),
    effective_from DATE NOT NULL,
    base_salary_vnd NUMERIC(15, 2) NOT NULL CHECK (base_salary_vnd >= 0),
    rate_vnd_per_kg NUMERIC(15, 2) NOT NULL CHECK (rate_vnd_per_kg >= 0),
    kpi_target_kg NUMERIC(15, 3),
    kpi_bonus_vnd NUMERIC(15, 2) NOT NULL DEFAULT 0,
    created_at VARCHAR(19) NOT NULL
        DEFAULT to_char(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYYY-MM-DD HH24:MI:SS')
        CHECK (created_at ~ '^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01]) ([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]$'),
    CONSTRAINT uk_company_warehouse_payroll_rate_effective UNIQUE (company_id, effective_from),
    -- Không dùng KPI thì tiền thưởng là 0; có chỉ tiêu thì chỉ tiêu và tiền thưởng đều phải lớn hơn 0.
    CONSTRAINT chk_company_warehouse_payroll_rates_kpi CHECK (
        (kpi_target_kg IS NULL AND kpi_bonus_vnd = 0)
        OR (kpi_target_kg > 0 AND kpi_bonus_vnd > 0)
    )
);

CREATE INDEX IF NOT EXISTS idx_company_warehouse_payroll_rates_company_effective
    ON company_warehouse_payroll_rates(company_id, effective_from DESC);

CREATE TABLE IF NOT EXISTS warehouse_payroll_periods (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES companies(id),
    payroll_rate_id BIGINT NOT NULL REFERENCES company_warehouse_payroll_rates(id),
    period_start DATE NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'LOCKED')),
    locked_at VARCHAR(19)
        CHECK (locked_at IS NULL OR locked_at ~ '^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01]) ([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]$'),
    CONSTRAINT uk_warehouse_payroll_period_company_start UNIQUE (company_id, period_start)
);

CREATE INDEX IF NOT EXISTS idx_warehouse_payroll_periods_company_start
    ON warehouse_payroll_periods(company_id, period_start);

CREATE TABLE IF NOT EXISTS warehouse_payrolls (
    id BIGSERIAL PRIMARY KEY,
    payroll_period_id BIGINT NOT NULL REFERENCES warehouse_payroll_periods(id) ON DELETE CASCADE,
    worker_user_id BIGINT NOT NULL REFERENCES users(id),
    base_salary_vnd NUMERIC(15, 0) NOT NULL CHECK (base_salary_vnd >= 0),
    loaded_packages INTEGER NOT NULL DEFAULT 0 CHECK (loaded_packages >= 0),
    loaded_kg NUMERIC(15, 3) NOT NULL CHECK (loaded_kg >= 0),
    loaded_amount_vnd NUMERIC(15, 0) NOT NULL CHECK (loaded_amount_vnd >= 0),
    kpi_achieved BOOLEAN NOT NULL DEFAULT FALSE,
    kpi_bonus_vnd NUMERIC(15, 0) NOT NULL DEFAULT 0 CHECK (kpi_bonus_vnd >= 0),
    total_salary_vnd NUMERIC(15, 0) NOT NULL CHECK (total_salary_vnd >= 0),
    CONSTRAINT uk_warehouse_payroll_period_worker UNIQUE (payroll_period_id, worker_user_id)
);

CREATE TABLE IF NOT EXISTS warehouse_payroll_trip_lines (
    id BIGSERIAL PRIMARY KEY,
    warehouse_payroll_id BIGINT NOT NULL REFERENCES warehouse_payrolls(id) ON DELETE CASCADE,
    trip_id BIGINT NOT NULL REFERENCES trips(id),
    loaded_date DATE NOT NULL,
    loaded_packages INTEGER NOT NULL CHECK (loaded_packages >= 0),
    loaded_kg NUMERIC(15, 3) NOT NULL CHECK (loaded_kg >= 0),
    CONSTRAINT uk_warehouse_payroll_trip UNIQUE (warehouse_payroll_id, trip_id)
);

CREATE TABLE IF NOT EXISTS warehouse_base_salary_overrides (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES companies(id),
    worker_user_id BIGINT NOT NULL REFERENCES users(id),
    effective_from DATE NOT NULL,
    base_salary_vnd NUMERIC(15, 2) NOT NULL CHECK (base_salary_vnd >= 0),
    created_at VARCHAR(19) NOT NULL
        DEFAULT to_char(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYYY-MM-DD HH24:MI:SS')
        CHECK (created_at ~ '^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01]) ([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]$'),
    CONSTRAINT uk_warehouse_base_salary_effective UNIQUE (worker_user_id, effective_from)
);

CREATE INDEX IF NOT EXISTS idx_warehouse_base_salary_overrides_worker_effective
    ON warehouse_base_salary_overrides(worker_user_id, effective_from DESC);
