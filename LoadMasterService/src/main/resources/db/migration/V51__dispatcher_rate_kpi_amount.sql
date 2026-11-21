ALTER TABLE company_dispatcher_payroll_rates
    ADD COLUMN IF NOT EXISTS kpi_amount_vnd numeric(15, 2);

UPDATE company_dispatcher_payroll_rates
SET kpi_amount_vnd = 0
WHERE kpi_amount_vnd IS NULL;

ALTER TABLE company_dispatcher_payroll_rates
    ALTER COLUMN kpi_amount_vnd SET DEFAULT 0;

ALTER TABLE company_dispatcher_payroll_rates
    ALTER COLUMN kpi_amount_vnd SET NOT NULL;

ALTER TABLE company_dispatcher_payroll_rates
    DROP CONSTRAINT IF EXISTS company_dispatcher_payroll_rates_kpi_amount_vnd_check;

ALTER TABLE company_dispatcher_payroll_rates
    ADD CONSTRAINT company_dispatcher_payroll_rates_kpi_amount_vnd_check
        CHECK (kpi_amount_vnd >= 0);
