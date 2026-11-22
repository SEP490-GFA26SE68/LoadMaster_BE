-- Review-only inspection. No DDL or application data writes.
BEGIN TRANSACTION READ ONLY;

SELECT current_database(), current_schema(), current_setting('TimeZone') AS session_timezone;

SELECT table_schema, column_name, data_type, udt_name, is_nullable,
       datetime_precision, column_default
FROM information_schema.columns
WHERE table_name = 'users' AND column_name IN ('created_at', 'updated_at')
ORDER BY table_schema, column_name;

SELECT n.nspname AS table_schema, c.conname, c.convalidated,
       pg_get_constraintdef(c.oid) AS definition
FROM pg_constraint c
JOIN pg_class t ON t.oid = c.conrelid
JOIN pg_namespace n ON n.oid = t.relnamespace
WHERE t.relname = 'users' AND c.contype = 'c'
ORDER BY n.nspname, c.conname;

SELECT n.nspname AS table_schema, tg.tgname, pg_get_triggerdef(tg.oid) AS definition
FROM pg_trigger tg
JOIN pg_class t ON t.oid = tg.tgrelid
JOIN pg_namespace n ON n.oid = t.relnamespace
WHERE t.relname = 'users' AND NOT tg.tgisinternal;

-- Run these only after confirming that public.users is the intended table.
SELECT count(*) AS total_users,
       count(*) FILTER (WHERE created_at IS NULL) AS missing_created_at,
       count(*) FILTER (WHERE updated_at IS NULL) AS missing_updated_at,
       count(*) FILTER (WHERE updated_at < created_at) AS invalid_order
FROM public.users;

ROLLBACK;
