-- V25: Enforce phone number uniqueness and reject VN phone numbers with 8 identical trailing digits (e.g. 0911111111)

-- 1. Update guest_registration_sessions phone format check constraint
ALTER TABLE public.guest_registration_sessions
    DROP CONSTRAINT IF EXISTS chk_guest_reg_phone_format;

UPDATE public.guest_registration_sessions
SET phone_number = '0901234567'
WHERE phone_number IS NOT NULL
  AND regexp_replace(phone_number, '[\s.-]', '', 'g') ~ '^(0[35789]|\+?84[35789])(0{8}|1{8}|2{8}|3{8}|4{8}|5{8}|6{8}|7{8}|8{8}|9{8})$';

ALTER TABLE public.guest_registration_sessions
    ADD CONSTRAINT chk_guest_reg_phone_format
    CHECK (
        phone_number IS NULL
        OR (
            regexp_replace(phone_number, '[\s.-]', '', 'g') ~ '^(0[35789][0-9]{8}|(\+?84)[35789][0-9]{8}|(\+?65)?[89][0-9]{7}|(\+?1)?[2-9][0-9]{9}|(0|\+?81)[789]0[0-9]{8}|(0|\+?82)10[0-9]{8}|\+?[0-9]{8,15})$'
            AND regexp_replace(phone_number, '[\s.-]', '', 'g') !~ '^(0[35789]|\+?84[35789])(0{8}|1{8}|2{8}|3{8}|4{8}|5{8}|6{8}|7{8}|8{8}|9{8})$'
        )
    );

-- 2. Add phone format & anti-repeated-8-digits constraint on trial_registration_sessions
ALTER TABLE public.trial_registration_sessions
    DROP CONSTRAINT IF EXISTS chk_trial_reg_phone_format;

UPDATE public.trial_registration_sessions
SET phone_number = '0901234567'
WHERE phone_number IS NOT NULL
  AND regexp_replace(phone_number, '[\s.-]', '', 'g') ~ '^(0[35789]|\+?84[35789])(0{8}|1{8}|2{8}|3{8}|4{8}|5{8}|6{8}|7{8}|8{8}|9{8})$';

ALTER TABLE public.trial_registration_sessions
    ADD CONSTRAINT chk_trial_reg_phone_format
    CHECK (
        phone_number IS NULL
        OR (
            regexp_replace(phone_number, '[\s.-]', '', 'g') ~ '^(0[35789][0-9]{8}|(\+?84)[35789][0-9]{8}|(\+?65)?[89][0-9]{7}|(\+?1)?[2-9][0-9]{9}|(0|\+?81)[789]0[0-9]{8}|(0|\+?82)10[0-9]{8}|\+?[0-9]{8,15})$'
            AND regexp_replace(phone_number, '[\s.-]', '', 'g') !~ '^(0[35789]|\+?84[35789])(0{8}|1{8}|2{8}|3{8}|4{8}|5{8}|6{8}|7{8}|8{8}|9{8})$'
        )
    );

-- 3. Add phone format & anti-repeated-8-digits constraint on trial_account_requests
ALTER TABLE public.trial_account_requests
    DROP CONSTRAINT IF EXISTS chk_trial_account_requests_phone_format;

UPDATE public.trial_account_requests
SET phone_number = '0901234567'
WHERE phone_number IS NOT NULL
  AND regexp_replace(phone_number, '[\s.-]', '', 'g') ~ '^(0[35789]|\+?84[35789])(0{8}|1{8}|2{8}|3{8}|4{8}|5{8}|6{8}|7{8}|8{8}|9{8})$';

ALTER TABLE public.trial_account_requests
    ADD CONSTRAINT chk_trial_account_requests_phone_format
    CHECK (
        phone_number IS NULL
        OR (
            regexp_replace(phone_number, '[\s.-]', '', 'g') ~ '^(0[35789][0-9]{8}|(\+?84)[35789][0-9]{8}|(\+?65)?[89][0-9]{7}|(\+?1)?[2-9][0-9]{9}|(0|\+?81)[789]0[0-9]{8}|(0|\+?82)10[0-9]{8}|\+?[0-9]{8,15})$'
            AND regexp_replace(phone_number, '[\s.-]', '', 'g') !~ '^(0[35789]|\+?84[35789])(0{8}|1{8}|2{8}|3{8}|4{8}|5{8}|6{8}|7{8}|8{8}|9{8})$'
        )
    );

-- 4. Deduplicate any existing duplicate phone_number in public.users before adding unique index
WITH ranked_users AS (
    SELECT id, phone_number,
           ROW_NUMBER() OVER (PARTITION BY phone_number ORDER BY id ASC) AS rn
    FROM public.users
    WHERE phone_number IS NOT NULL AND btrim(phone_number) <> ''
)
UPDATE public.users u
SET phone_number = NULL
FROM ranked_users r
WHERE u.id = r.id AND r.rn > 1;

CREATE UNIQUE INDEX IF NOT EXISTS uk_users_phone_number
    ON public.users (phone_number)
    WHERE phone_number IS NOT NULL AND btrim(phone_number) <> '';

