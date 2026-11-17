CREATE TABLE IF NOT EXISTS company_payroll_rates (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES companies(id),
    effective_from DATE NOT NULL,
    base_salary_vnd NUMERIC(15, 2) NOT NULL CHECK (base_salary_vnd >= 0),
    rate_vnd_per_kg NUMERIC(15, 2) NOT NULL CHECK (rate_vnd_per_kg >= 0),
    rate_vnd_per_km NUMERIC(15, 2) NOT NULL CHECK (rate_vnd_per_km >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_company_payroll_rate_effective UNIQUE (company_id, effective_from)
);

CREATE TABLE IF NOT EXISTS payroll_periods (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES companies(id),
    payroll_rate_id BIGINT NOT NULL REFERENCES company_payroll_rates(id),
    period_start DATE NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'LOCKED')),
    locked_at TIMESTAMP,
    CONSTRAINT uk_payroll_period_company_start UNIQUE (company_id, period_start)
);

CREATE TABLE IF NOT EXISTS driver_payrolls (
    id BIGSERIAL PRIMARY KEY,
    payroll_period_id BIGINT NOT NULL REFERENCES payroll_periods(id) ON DELETE CASCADE,
    driver_user_id BIGINT NOT NULL REFERENCES users(id),
    base_salary_vnd NUMERIC(15, 0) NOT NULL CHECK (base_salary_vnd >= 0),
    total_kg NUMERIC(15, 3) NOT NULL DEFAULT 0 CHECK (total_kg >= 0),
    kg_amount_vnd NUMERIC(15, 0) NOT NULL DEFAULT 0 CHECK (kg_amount_vnd >= 0),
    total_km NUMERIC(15, 3) NOT NULL DEFAULT 0 CHECK (total_km >= 0),
    km_amount_vnd NUMERIC(15, 0) NOT NULL DEFAULT 0 CHECK (km_amount_vnd >= 0),
    total_salary_vnd NUMERIC(15, 0) NOT NULL CHECK (total_salary_vnd >= 0),
    CONSTRAINT uk_driver_payroll_period_driver UNIQUE (payroll_period_id, driver_user_id)
);

CREATE TABLE IF NOT EXISTS driver_payroll_trip_lines (
    id BIGSERIAL PRIMARY KEY,
    driver_payroll_id BIGINT NOT NULL REFERENCES driver_payrolls(id) ON DELETE CASCADE,
    trip_id BIGINT NOT NULL REFERENCES trips(id),
    load_plan_id BIGINT NOT NULL REFERENCES load_plans(id),
    route_plan_id BIGINT NOT NULL REFERENCES route_plans(id),
    plan_kg NUMERIC(15, 3) NOT NULL CHECK (plan_kg >= 0),
    plan_km NUMERIC(15, 3) NOT NULL CHECK (plan_km >= 0),
    share_percent NUMERIC(7, 4) NOT NULL CHECK (share_percent > 0 AND share_percent <= 100),
    allocated_kg NUMERIC(15, 3) NOT NULL CHECK (allocated_kg >= 0),
    allocated_km NUMERIC(15, 3) NOT NULL CHECK (allocated_km >= 0),
    rate_vnd_per_kg NUMERIC(15, 2) NOT NULL CHECK (rate_vnd_per_kg >= 0),
    rate_vnd_per_km NUMERIC(15, 2) NOT NULL CHECK (rate_vnd_per_km >= 0),
    kg_amount_vnd NUMERIC(15, 0) NOT NULL CHECK (kg_amount_vnd >= 0),
    km_amount_vnd NUMERIC(15, 0) NOT NULL CHECK (km_amount_vnd >= 0),
    total_amount_vnd NUMERIC(15, 0) NOT NULL CHECK (total_amount_vnd >= 0),
    CONSTRAINT uk_driver_payroll_trip UNIQUE (driver_payroll_id, trip_id)
);

CREATE TABLE IF NOT EXISTS trip_payroll_driver_shares (
    id BIGSERIAL PRIMARY KEY,
    trip_id BIGINT NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
    driver_user_id BIGINT NOT NULL REFERENCES users(id),
    share_percent NUMERIC(7, 4) NOT NULL CHECK (share_percent > 0 AND share_percent <= 100),
    CONSTRAINT uk_trip_payroll_driver UNIQUE (trip_id, driver_user_id)
);

CREATE INDEX IF NOT EXISTS idx_payroll_periods_company_start ON payroll_periods(company_id, period_start);
CREATE INDEX IF NOT EXISTS idx_driver_payroll_trip_lines_trip ON driver_payroll_trip_lines(trip_id);
CREATE INDEX IF NOT EXISTS idx_trip_payroll_driver_shares_trip ON trip_payroll_driver_shares(trip_id);
