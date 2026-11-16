-- Match support_tickets / company_payroll_rates: Vietnam local time as yyyy-MM-dd HH:mm:ss (varchar(19)).
-- Existing TIMESTAMP values are converted to 19-char format.
ALTER TABLE public.payroll_periods
    DROP CONSTRAINT IF EXISTS payroll_periods_locked_at_check;

ALTER TABLE public.payroll_periods
    ALTER COLUMN locked_at DROP DEFAULT;

ALTER TABLE public.payroll_periods
    ALTER COLUMN locked_at TYPE varchar(19)
    USING CASE
        WHEN locked_at IS NULL THEN NULL
        ELSE substring(replace(locked_at::text, 'T', ' ') from 1 for 19)
    END;

ALTER TABLE public.payroll_periods
    ADD CONSTRAINT payroll_periods_locked_at_check
    CHECK (locked_at IS NULL OR locked_at ~ '^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01]) ([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]$');
