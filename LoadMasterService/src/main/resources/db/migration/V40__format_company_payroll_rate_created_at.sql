-- Match support_tickets.created_at: Vietnam local time as yyyy-MM-dd HH:mm:ss.
-- Existing TIMESTAMPTZ values retain their instant when converted from UTC.
ALTER TABLE public.company_payroll_rates
    DROP CONSTRAINT IF EXISTS company_payroll_rates_created_at_check;

ALTER TABLE public.company_payroll_rates
    ALTER COLUMN created_at DROP DEFAULT;

ALTER TABLE public.company_payroll_rates
    ALTER COLUMN created_at TYPE varchar(19)
    USING to_char(created_at AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYYY-MM-DD HH24:MI:SS');

ALTER TABLE public.company_payroll_rates
    ALTER COLUMN created_at SET DEFAULT to_char(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYYY-MM-DD HH24:MI:SS');

ALTER TABLE public.company_payroll_rates
    ADD CONSTRAINT company_payroll_rates_created_at_check
    CHECK (created_at ~ '^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01]) ([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]$');
