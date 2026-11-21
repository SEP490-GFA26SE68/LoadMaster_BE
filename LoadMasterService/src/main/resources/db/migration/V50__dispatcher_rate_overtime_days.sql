ALTER TABLE company_dispatcher_payroll_rates
    ADD COLUMN IF NOT EXISTS overtime_days_per_month INTEGER;

UPDATE company_dispatcher_payroll_rates
SET overtime_days_per_month = 0
WHERE overtime_days_per_month IS NULL;

ALTER TABLE company_dispatcher_payroll_rates
    ALTER COLUMN overtime_days_per_month SET DEFAULT 0;

ALTER TABLE company_dispatcher_payroll_rates
    ALTER COLUMN overtime_days_per_month SET NOT NULL;

ALTER TABLE company_dispatcher_payroll_rates
    DROP CONSTRAINT IF EXISTS company_dispatcher_payroll_rates_overtime_days_per_month_check;

ALTER TABLE company_dispatcher_payroll_rates
    ADD CONSTRAINT company_dispatcher_payroll_rates_overtime_days_per_month_check
        CHECK (overtime_days_per_month >= 0 AND overtime_days_per_month <= 31);
