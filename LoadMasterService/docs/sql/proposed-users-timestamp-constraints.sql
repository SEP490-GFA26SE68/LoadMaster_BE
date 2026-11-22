-- PROPOSAL ONLY. Not a Flyway migration; never executed by the application.
-- Review docs/user-timestamps-utc.md and rerun inspect-users-timestamps.sql first.
-- Run on a snapshot before any approved shared deployment. No data is rewritten.
-- The default ROLLBACK is deliberate. Only a reviewer may change it to COMMIT.
BEGIN;
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '30s';

-- Keep preflight and DDL consistent if other sessions are inserting/updating users.
LOCK TABLE public.users IN ACCESS EXCLUSIVE MODE;

DO $$
BEGIN
    IF (SELECT count(*) FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'users'
          AND column_name IN ('created_at', 'updated_at')
          AND data_type = 'timestamp with time zone') <> 2 THEN
        RAISE EXCEPTION 'Expected two existing TIMESTAMPTZ columns; review schema, do not infer a legacy timezone';
    END IF;
    IF EXISTS (SELECT 1 FROM public.users
               WHERE created_at IS NULL OR updated_at IS NULL OR updated_at < created_at) THEN
        RAISE EXCEPTION 'Existing timestamps violate the proposed constraints; aborting without repairing or overwriting data';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_constraint
               WHERE conrelid = 'public.users'::regclass AND conname = 'chk_users_updated_after_created') THEN
        RAISE EXCEPTION 'Ordering constraint already exists; inspect its definition instead of replacing it';
    END IF;
END $$;

ALTER TABLE public.users ALTER COLUMN updated_at SET NOT NULL;
ALTER TABLE public.users
    ADD CONSTRAINT chk_users_updated_after_created CHECK (updated_at >= created_at);

-- Inspect the proposed result inside this transaction.
SELECT column_name, data_type, is_nullable
FROM information_schema.columns
WHERE table_schema = 'public' AND table_name = 'users'
  AND column_name IN ('created_at', 'updated_at');
SELECT conname, pg_get_constraintdef(oid)
FROM pg_constraint
WHERE conrelid = 'public.users'::regclass AND conname = 'chk_users_updated_after_created';

ROLLBACK;
