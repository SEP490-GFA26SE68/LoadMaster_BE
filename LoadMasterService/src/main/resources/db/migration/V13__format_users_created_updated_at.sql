-- Format users.created_at and users.updated_at as 'yyyy-MM-dd HH:mm:ss' (varchar(19), NOT NULL)
-- and enforce format + chronological order at the database level.

ALTER TABLE public.users DROP CONSTRAINT IF EXISTS users_created_at_check;
ALTER TABLE public.users DROP CONSTRAINT IF EXISTS users_updated_at_check;
ALTER TABLE public.users DROP CONSTRAINT IF EXISTS chk_users_updated_after_created;

ALTER TABLE public.users ALTER COLUMN created_at DROP DEFAULT;
ALTER TABLE public.users ALTER COLUMN updated_at DROP DEFAULT;

ALTER TABLE public.users
    ALTER COLUMN created_at TYPE varchar(19)
    USING CASE
        WHEN created_at IS NULL THEN to_char(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYYY-MM-DD HH24:MI:SS')
        WHEN pg_typeof(created_at)::text LIKE 'timestamp%' THEN to_char(created_at::timestamptz AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYYY-MM-DD HH24:MI:SS')
        ELSE substring(replace(created_at::text, 'T', ' ') from 1 for 19)
    END;

ALTER TABLE public.users
    ALTER COLUMN updated_at TYPE varchar(19)
    USING CASE
        WHEN updated_at IS NULL THEN NULL
        WHEN pg_typeof(updated_at)::text LIKE 'timestamp%' THEN to_char(updated_at::timestamptz AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYYY-MM-DD HH24:MI:SS')
        ELSE substring(replace(updated_at::text, 'T', ' ') from 1 for 19)
    END;

UPDATE public.users
SET created_at = to_char(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYYY-MM-DD HH24:MI:SS')
WHERE created_at IS NULL
   OR created_at !~ '^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01]) ([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]$';

UPDATE public.users
SET updated_at = created_at
WHERE updated_at IS NULL
   OR updated_at !~ '^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01]) ([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]$'
   OR updated_at < created_at;

ALTER TABLE public.users
    ALTER COLUMN created_at SET NOT NULL,
    ALTER COLUMN created_at SET DEFAULT to_char(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYYY-MM-DD HH24:MI:SS');

ALTER TABLE public.users
    ALTER COLUMN updated_at SET NOT NULL,
    ALTER COLUMN updated_at SET DEFAULT to_char(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYYY-MM-DD HH24:MI:SS');

ALTER TABLE public.users
    ADD CONSTRAINT users_created_at_check
    CHECK (created_at ~ '^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01]) ([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]$');

ALTER TABLE public.users
    ADD CONSTRAINT users_updated_at_check
    CHECK (updated_at ~ '^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01]) ([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]$');

ALTER TABLE public.users
    ADD CONSTRAINT chk_users_updated_after_created
    CHECK (updated_at >= created_at);

