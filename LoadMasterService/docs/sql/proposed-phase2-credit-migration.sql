-- SUPERSEDED for deployment by docs/sql/phase2c/README.md and its numbered scripts.
-- Historical proposal retained for review/tests only. DO NOT APPLY this old file.
-- PROPOSED ONLY. Not in db/migration; never executed by this change.
-- Review backups, preflight results, current column types, index names and CHECK definitions first.
-- Existing Flyway history has duplicate V13/V14/V15 versions; do not enable Flyway to run this script.
-- No balance writes, data deletion or timestamp conversion. The transaction aborts on invalid legacy data.
BEGIN;
SET LOCAL lock_timeout='5s';
SET LOCAL statement_timeout='60s';
DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM credit_transactions WHERE type='USAGE'
           GROUP BY reference HAVING count(*)>1 OR reference IS NULL OR btrim(reference)='') THEN
  RAISE EXCEPTION 'Invalid/duplicate USAGE references: reconcile manually, do not delete ledger rows';
 END IF;
 IF EXISTS(SELECT 1 FROM optimization_jobs GROUP BY job_uuid
           HAVING count(*)>1 OR job_uuid IS NULL OR btrim(job_uuid)='') THEN
  RAISE EXCEPTION 'Invalid/duplicate job UUIDs: reconcile manually before migration';
 END IF;
END $$;
ALTER TABLE optimization_jobs ADD COLUMN IF NOT EXISTS company_id varchar(64);
ALTER TABLE optimization_jobs ADD COLUMN IF NOT EXISTS subscription_tier varchar(50);
ALTER TABLE optimization_jobs ADD COLUMN IF NOT EXISTS algorithm_tier varchar(50);
ALTER TABLE optimization_jobs ADD COLUMN IF NOT EXISTS parent_plan_id bigint;
ALTER TABLE optimization_jobs ADD COLUMN IF NOT EXISTS seed integer;
-- IF NOT EXISTS does not validate existing column types. Confirm against preflight; abort if incompatible.
-- Legacy jobs are intentionally NOT backfilled or authorized to run by this migration.
CREATE UNIQUE INDEX IF NOT EXISTS uk_phase2_job_uuid ON optimization_jobs(job_uuid);
CREATE UNIQUE INDEX IF NOT EXISTS uk_phase2_credit_usage_reference
 ON credit_transactions(reference) WHERE type='USAGE';
-- READ ONLY metadata inspection on 2026-11-05 confirmed this exact old CHECK on the current DB.
-- Refuse to replace a definition that differs from the reviewed old enum-list constraint.
DO $migration$
DECLARE old_definition text;
BEGIN
 SELECT pg_get_constraintdef(oid) INTO old_definition FROM pg_constraint
 WHERE conrelid='optimization_jobs'::regclass AND conname='optimization_jobs_algorithm_name_check';
 IF old_definition IS DISTINCT FROM $expected$CHECK (((algorithm_name)::text = ANY ((ARRAY['EP_DBLF'::character varying, 'GENETIC_ALGORITHM'::character varying])::text[])))$expected$ THEN
  RAISE EXCEPTION 'Unexpected algorithm CHECK; review its definition manually before replacing it';
 END IF;
END $migration$;
ALTER TABLE optimization_jobs DROP CONSTRAINT optimization_jobs_algorithm_name_check;
ALTER TABLE optimization_jobs ADD CONSTRAINT optimization_jobs_algorithm_name_check CHECK
 (algorithm_name IS NULL OR algorithm_name IN ('EP_DBLF','EP_DBLF_GA','EP_DBLF_GA_AI','GENETIC_ALGORITHM'));
-- Existing enum CHECKs may reject the new server-selected algorithm names.
-- Inspect each old definition; preserve additional business constraints rather than dropping blindly.
-- After review, replace ONLY an enum-list CHECK on algorithm_name with one accepting:
-- EP_DBLF, EP_DBLF_GA, EP_DBLF_GA_AI, GENETIC_ALGORITHM.
-- Example (replace actual_enum_check_name with the inspected name; not executable as-is):
-- ALTER TABLE optimization_jobs DROP CONSTRAINT actual_enum_check_name;
-- ALTER TABLE optimization_jobs ADD CONSTRAINT chk_phase2_algorithm_name CHECK
--   (algorithm_name IS NULL OR algorithm_name IN ('EP_DBLF','EP_DBLF_GA','EP_DBLF_GA_AI','GENETIC_ALGORITHM'));
-- Existing PRO catalog rows are NOT changed by the initializer fix. Review a separate catalog correction:
-- UPDATE subscription_plans SET monthly_credits=15000
-- WHERE plan_code='PRO_MONTHLY' AND tier='PRO' AND monthly_credits=10000;
-- This does not grant credits or change an existing account balance. Decide its effective billing cycle first.
COMMIT;
