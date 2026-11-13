-- V24: Format timestamps in trial_account_requests and trial_registration_sessions
-- as 'yyyy-MM-dd HH:mm:ss' (varchar(19)) identical to support_tickets,
-- and enforce format + chronological order CHECK constraints in DB.

-- ============================================================================
-- 1. public.trial_account_requests (created_at, resolved_at)
-- ============================================================================
ALTER TABLE public.trial_account_requests DROP CONSTRAINT IF EXISTS trial_account_requests_created_at_check;
ALTER TABLE public.trial_account_requests DROP CONSTRAINT IF EXISTS trial_account_requests_resolved_at_check;
ALTER TABLE public.trial_account_requests DROP CONSTRAINT IF EXISTS chk_trial_account_requests_resolved_after_created;

ALTER TABLE public.trial_account_requests ALTER COLUMN created_at DROP DEFAULT;
ALTER TABLE public.trial_account_requests ALTER COLUMN resolved_at DROP DEFAULT;

ALTER TABLE public.trial_account_requests
    ALTER COLUMN created_at TYPE varchar(19)
    USING CASE
        WHEN created_at IS NULL THEN to_char(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYYY-MM-DD HH24:MI:SS')
        ELSE substring(replace(created_at::text, 'T', ' ') from 1 for 19)
    END;

ALTER TABLE public.trial_account_requests
    ALTER COLUMN resolved_at TYPE varchar(19)
    USING CASE
        WHEN resolved_at IS NULL THEN NULL
        ELSE substring(replace(resolved_at::text, 'T', ' ') from 1 for 19)
    END;

UPDATE public.trial_account_requests
SET created_at = to_char(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYYY-MM-DD HH24:MI:SS')
WHERE created_at IS NULL
   OR created_at !~ '^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01]) ([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]$';

UPDATE public.trial_account_requests
SET resolved_at = created_at
WHERE resolved_at IS NOT NULL
  AND (resolved_at !~ '^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01]) ([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]$'
       OR resolved_at < created_at);

ALTER TABLE public.trial_account_requests
    ALTER COLUMN created_at SET NOT NULL,
    ALTER COLUMN created_at SET DEFAULT to_char(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYYY-MM-DD HH24:MI:SS');

ALTER TABLE public.trial_account_requests
    ADD CONSTRAINT trial_account_requests_created_at_check
    CHECK (created_at ~ '^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01]) ([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]$');

ALTER TABLE public.trial_account_requests
    ADD CONSTRAINT trial_account_requests_resolved_at_check
    CHECK (resolved_at IS NULL OR resolved_at ~ '^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01]) ([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]$');

ALTER TABLE public.trial_account_requests
    ADD CONSTRAINT chk_trial_account_requests_resolved_after_created
    CHECK (resolved_at IS NULL OR resolved_at >= created_at);

-- ============================================================================
-- 2. public.trial_registration_sessions
--    (created_at, expires_at, otp_expires_at, otp_resend_allowed_at)
-- ============================================================================
ALTER TABLE public.trial_registration_sessions DROP CONSTRAINT IF EXISTS trial_registration_sessions_created_at_check;
ALTER TABLE public.trial_registration_sessions DROP CONSTRAINT IF EXISTS trial_registration_sessions_expires_at_check;
ALTER TABLE public.trial_registration_sessions DROP CONSTRAINT IF EXISTS trial_registration_sessions_otp_expires_at_check;
ALTER TABLE public.trial_registration_sessions DROP CONSTRAINT IF EXISTS trial_registration_sessions_otp_resend_allowed_at_check;
ALTER TABLE public.trial_registration_sessions DROP CONSTRAINT IF EXISTS chk_trial_reg_timestamps_after_created;
ALTER TABLE public.trial_registration_sessions DROP CONSTRAINT IF EXISTS chk_trial_reg_expires_after_created;
ALTER TABLE public.trial_registration_sessions DROP CONSTRAINT IF EXISTS chk_trial_reg_otp_expires_after_created;
ALTER TABLE public.trial_registration_sessions DROP CONSTRAINT IF EXISTS chk_trial_reg_otp_resend_after_created;

ALTER TABLE public.trial_registration_sessions ALTER COLUMN created_at DROP DEFAULT;
ALTER TABLE public.trial_registration_sessions ALTER COLUMN expires_at DROP DEFAULT;
ALTER TABLE public.trial_registration_sessions ALTER COLUMN otp_expires_at DROP DEFAULT;
ALTER TABLE public.trial_registration_sessions ALTER COLUMN otp_resend_allowed_at DROP DEFAULT;

ALTER TABLE public.trial_registration_sessions
    ALTER COLUMN created_at TYPE varchar(19)
    USING CASE
        WHEN created_at IS NULL THEN to_char(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYYY-MM-DD HH24:MI:SS')
        ELSE substring(replace(created_at::text, 'T', ' ') from 1 for 19)
    END;

ALTER TABLE public.trial_registration_sessions
    ALTER COLUMN expires_at TYPE varchar(19)
    USING CASE
        WHEN expires_at IS NULL THEN to_char((CURRENT_TIMESTAMP + INTERVAL '24 hours') AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYYY-MM-DD HH24:MI:SS')
        ELSE substring(replace(expires_at::text, 'T', ' ') from 1 for 19)
    END;

ALTER TABLE public.trial_registration_sessions
    ALTER COLUMN otp_expires_at TYPE varchar(19)
    USING CASE
        WHEN otp_expires_at IS NULL THEN NULL
        ELSE substring(replace(otp_expires_at::text, 'T', ' ') from 1 for 19)
    END;

ALTER TABLE public.trial_registration_sessions
    ALTER COLUMN otp_resend_allowed_at TYPE varchar(19)
    USING CASE
        WHEN otp_resend_allowed_at IS NULL THEN NULL
        ELSE substring(replace(otp_resend_allowed_at::text, 'T', ' ') from 1 for 19)
    END;

UPDATE public.trial_registration_sessions
SET created_at = to_char(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYYY-MM-DD HH24:MI:SS')
WHERE created_at IS NULL
   OR created_at !~ '^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01]) ([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]$';

UPDATE public.trial_registration_sessions
SET expires_at = created_at
WHERE expires_at IS NULL
   OR expires_at !~ '^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01]) ([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]$'
   OR expires_at < created_at;

UPDATE public.trial_registration_sessions
SET otp_expires_at = created_at
WHERE otp_expires_at IS NOT NULL
  AND (otp_expires_at !~ '^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01]) ([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]$'
       OR otp_expires_at < created_at);

UPDATE public.trial_registration_sessions
SET otp_resend_allowed_at = created_at
WHERE otp_resend_allowed_at IS NOT NULL
  AND (otp_resend_allowed_at !~ '^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01]) ([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]$'
       OR otp_resend_allowed_at < created_at);

ALTER TABLE public.trial_registration_sessions
    ALTER COLUMN created_at SET NOT NULL,
    ALTER COLUMN created_at SET DEFAULT to_char(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYYY-MM-DD HH24:MI:SS'),
    ALTER COLUMN expires_at SET NOT NULL;

ALTER TABLE public.trial_registration_sessions
    ADD CONSTRAINT trial_registration_sessions_created_at_check
    CHECK (created_at ~ '^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01]) ([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]$');

ALTER TABLE public.trial_registration_sessions
    ADD CONSTRAINT trial_registration_sessions_expires_at_check
    CHECK (expires_at ~ '^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01]) ([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]$');

ALTER TABLE public.trial_registration_sessions
    ADD CONSTRAINT trial_registration_sessions_otp_expires_at_check
    CHECK (otp_expires_at IS NULL OR otp_expires_at ~ '^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01]) ([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]$');

ALTER TABLE public.trial_registration_sessions
    ADD CONSTRAINT trial_registration_sessions_otp_resend_allowed_at_check
    CHECK (otp_resend_allowed_at IS NULL OR otp_resend_allowed_at ~ '^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01]) ([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]$');

ALTER TABLE public.trial_registration_sessions
    ADD CONSTRAINT chk_trial_reg_timestamps_after_created
    CHECK (
        expires_at >= created_at
        AND (otp_expires_at IS NULL OR otp_expires_at >= created_at)
        AND (otp_resend_allowed_at IS NULL OR otp_resend_allowed_at >= created_at)
    );

-- ============================================================================
-- 3. public.support_tickets (ensure chronological constraint is also in DB)
-- ============================================================================
ALTER TABLE public.support_tickets DROP CONSTRAINT IF EXISTS chk_support_tickets_resolved_after_created;

UPDATE public.support_tickets
SET resolved_at = created_at
WHERE resolved_at IS NOT NULL
  AND created_at IS NOT NULL
  AND resolved_at < created_at;

ALTER TABLE public.support_tickets
    ADD CONSTRAINT chk_support_tickets_resolved_after_created
    CHECK (created_at IS NULL OR resolved_at IS NULL OR resolved_at >= created_at);

