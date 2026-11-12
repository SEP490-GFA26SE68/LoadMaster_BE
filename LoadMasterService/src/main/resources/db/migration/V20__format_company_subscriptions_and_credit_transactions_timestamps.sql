-- Format company_subscriptions.started_at, company_subscriptions.expires_at, and credit_transactions.created_at
-- as 'yyyy-MM-dd HH:mm:ss' (varchar(19)) and enforce format + chronological constraints at DB level.

-- 1. company_subscriptions
ALTER TABLE public.company_subscriptions DROP CONSTRAINT IF EXISTS company_subscriptions_started_at_check;
ALTER TABLE public.company_subscriptions DROP CONSTRAINT IF EXISTS company_subscriptions_expires_at_check;
ALTER TABLE public.company_subscriptions DROP CONSTRAINT IF EXISTS chk_company_subscriptions_expires_after_started;

ALTER TABLE public.company_subscriptions ALTER COLUMN started_at DROP DEFAULT;
ALTER TABLE public.company_subscriptions ALTER COLUMN expires_at DROP DEFAULT;

ALTER TABLE public.company_subscriptions
    ALTER COLUMN started_at TYPE varchar(19)
    USING CASE
        WHEN started_at IS NULL THEN NULL
        ELSE substring(replace(started_at::text, 'T', ' ') from 1 for 19)
    END;

ALTER TABLE public.company_subscriptions
    ALTER COLUMN expires_at TYPE varchar(19)
    USING CASE
        WHEN expires_at IS NULL THEN NULL
        ELSE substring(replace(expires_at::text, 'T', ' ') from 1 for 19)
    END;

UPDATE public.company_subscriptions
SET started_at = to_char(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYYY-MM-DD HH24:MI:SS')
WHERE started_at IS NOT NULL
  AND started_at !~ '^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01]) ([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]$';

UPDATE public.company_subscriptions
SET expires_at = started_at
WHERE expires_at IS NOT NULL
  AND started_at IS NOT NULL
  AND (expires_at !~ '^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01]) ([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]$'
       OR expires_at < started_at);

ALTER TABLE public.company_subscriptions
    ADD CONSTRAINT company_subscriptions_started_at_check
    CHECK (started_at IS NULL OR started_at ~ '^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01]) ([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]$');

ALTER TABLE public.company_subscriptions
    ADD CONSTRAINT company_subscriptions_expires_at_check
    CHECK (expires_at IS NULL OR expires_at ~ '^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01]) ([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]$');

ALTER TABLE public.company_subscriptions
    ADD CONSTRAINT chk_company_subscriptions_expires_after_started
    CHECK (started_at IS NULL OR expires_at IS NULL OR expires_at >= started_at);

-- 2. credit_transactions
ALTER TABLE public.credit_transactions DROP CONSTRAINT IF EXISTS credit_transactions_created_at_check;
ALTER TABLE public.credit_transactions ALTER COLUMN created_at DROP DEFAULT;

ALTER TABLE public.credit_transactions
    ALTER COLUMN created_at TYPE varchar(19)
    USING CASE
        WHEN created_at IS NULL THEN to_char(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYYY-MM-DD HH24:MI:SS')
        ELSE substring(replace(created_at::text, 'T', ' ') from 1 for 19)
    END;

UPDATE public.credit_transactions
SET created_at = to_char(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYYY-MM-DD HH24:MI:SS')
WHERE created_at IS NULL
   OR created_at !~ '^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01]) ([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]$';

ALTER TABLE public.credit_transactions
    ALTER COLUMN created_at SET NOT NULL,
    ALTER COLUMN created_at SET DEFAULT to_char(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYYY-MM-DD HH24:MI:SS');

ALTER TABLE public.credit_transactions
    ADD CONSTRAINT credit_transactions_created_at_check
    CHECK (created_at ~ '^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01]) ([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]$');

