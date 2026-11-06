-- SUPERSEDED for deployment by docs/sql/phase2c/README.md and its numbered scripts.
-- Historical proposal retained for review/tests only. DO NOT APPLY this old file.
-- READ ONLY. Review the results before considering the companion migration.
SELECT table_name,column_name,data_type,column_default,is_nullable
FROM information_schema.columns WHERE table_schema='public'
AND table_name IN ('optimization_jobs','credit_accounts','credit_transactions','subscription_plans')
ORDER BY table_name,ordinal_position;
SELECT reference,count(*) FROM credit_transactions WHERE type='USAGE'
GROUP BY reference HAVING count(*)>1 OR reference IS NULL OR btrim(reference)='';
SELECT job_uuid,count(*) FROM optimization_jobs GROUP BY job_uuid
HAVING count(*)>1 OR job_uuid IS NULL OR btrim(job_uuid)='';
SELECT c.conname,pg_get_constraintdef(c.oid) FROM pg_constraint c
WHERE c.conrelid='optimization_jobs'::regclass;
SELECT plan_code,tier,monthly_credits,algorithm_tier FROM subscription_plans;
-- Establish the original wall-clock zone for subscription started_at/expires_at with the team.
-- Do not convert legacy strings to UTC in this phase.
