-- Convert payroll_periods.locked_at from TIMESTAMP to varchar(19) yyyy-MM-dd HH:mm:ss.
-- Hibernate ddl-auto ALTER without USING fails: existing timestamps stringify longer than 19 chars.
-- Idempotent: skip the type change when the column is already varchar.

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'payroll_periods'
          AND column_name = 'locked_at'
          AND data_type LIKE 'timestamp%'
    ) THEN
        ALTER TABLE public.payroll_periods
            DROP CONSTRAINT IF EXISTS payroll_periods_locked_at_check;

        ALTER TABLE public.payroll_periods
            ALTER COLUMN locked_at DROP DEFAULT;

        ALTER TABLE public.payroll_periods
            ALTER COLUMN locked_at TYPE varchar(19)
            USING CASE
                WHEN locked_at IS NULL THEN NULL
                ELSE to_char(locked_at, 'YYYY-MM-DD HH24:MI:SS')
            END;
    END IF;
END $$;

ALTER TABLE public.payroll_periods
    DROP CONSTRAINT IF EXISTS payroll_periods_locked_at_check;

ALTER TABLE public.payroll_periods
    ADD CONSTRAINT payroll_periods_locked_at_check
    CHECK (locked_at IS NULL OR locked_at ~ '^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01]) ([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]$');
