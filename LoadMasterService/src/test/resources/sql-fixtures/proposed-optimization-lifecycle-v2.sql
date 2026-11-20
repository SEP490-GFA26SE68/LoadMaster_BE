-- PROPOSAL ONLY. Verify full clone and backup; stop/drain V1 writers first.
-- Never infer legacy non-ownership: existing rows retain NULL metadata.
BEGIN;
SET LOCAL lock_timeout = '5s';
ALTER TABLE public.optimization_jobs ADD COLUMN IF NOT EXISTS execution_generation bigint;
ALTER TABLE public.optimization_jobs ADD COLUMN IF NOT EXISTS dispatch_expires_at timestamptz;
ALTER TABLE public.optimization_jobs ADD COLUMN IF NOT EXISTS lease_expires_at timestamptz;
ALTER TABLE public.optimization_jobs ADD COLUMN IF NOT EXISTS execution_started_at timestamptz;
DO $$
DECLARE bad integer;
BEGIN
 SELECT count(*) INTO bad FROM information_schema.columns
 WHERE table_schema='public' AND table_name='optimization_jobs'
 AND ((column_name='execution_generation' AND (data_type<>'bigint' OR is_nullable<>'YES'))
   OR (column_name IN ('dispatch_expires_at','lease_expires_at','execution_started_at')
       AND (data_type<>'timestamp with time zone' OR is_nullable<>'YES')));
 IF bad<>0 THEN RAISE EXCEPTION 'Lifecycle metadata types/nullability mismatch'; END IF;
END $$;
COMMIT;
-- No data rewrite, indexes, pricing or status CHECK changes. Rerunnable.
-- Rollback: stop admission and drain V2 while retaining fenced finalizer/reconciler.
-- Do NOT drop columns/reset generations while any V2 worker may publish.
