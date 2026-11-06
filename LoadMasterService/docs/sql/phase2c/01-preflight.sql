-- READ ONLY. Run with ON_ERROR_STOP. No identifiers/references/balances from customer rows are returned.
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL statement_timeout = '30s';
SET LOCAL lock_timeout = '3s';

-- section: server
SELECT current_setting('server_version') AS server_version,
       current_setting('transaction_read_only') AS transaction_read_only,
       current_setting('TimeZone') AS session_timezone,transaction_timestamp() AS inspected_at;

-- section: columns
SELECT table_schema,table_name,column_name,data_type,udt_name,character_maximum_length,
       is_nullable,column_default
FROM information_schema.columns
WHERE table_schema='public' AND table_name IN
 ('optimization_jobs','credit_transactions','credit_accounts','subscription_plans')
ORDER BY table_name,ordinal_position;

-- section: constraints
SELECT n.nspname AS schema_name,t.relname AS table_name,c.conname,c.contype,c.convalidated,
       pg_get_constraintdef(c.oid) AS definition
FROM pg_constraint c JOIN pg_class t ON t.oid=c.conrelid
JOIN pg_namespace n ON n.oid=t.relnamespace
WHERE n.nspname='public' AND t.relname IN
 ('optimization_jobs','credit_transactions','credit_accounts','subscription_plans')
ORDER BY t.relname,c.conname;

-- section: indexes
SELECT t.relname AS table_name,idx.relname AS index_name,i.indisunique,i.indisvalid,i.indisready,
       i.indnkeyatts,pg_get_indexdef(i.indexrelid) AS definition,
       pg_get_expr(i.indpred,i.indrelid) AS predicate
FROM pg_index i JOIN pg_class t ON t.oid=i.indrelid
JOIN pg_class idx ON idx.oid=i.indexrelid JOIN pg_namespace n ON n.oid=t.relnamespace
WHERE n.nspname='public' AND t.relname IN
 ('optimization_jobs','credit_transactions','credit_accounts','subscription_plans')
ORDER BY t.relname,idx.relname;

-- section: algorithms
SELECT algorithm_name,count(*) AS jobs FROM public.optimization_jobs
GROUP BY algorithm_name ORDER BY algorithm_name NULLS FIRST;

-- section: user_triggers
SELECT c.relname AS table_name,t.tgname,t.tgenabled,pg_get_triggerdef(t.oid) AS definition
FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid JOIN pg_namespace n ON n.oid=c.relnamespace
WHERE n.nspname='public' AND NOT t.tgisinternal
 AND c.relname IN ('optimization_jobs','credit_transactions','credit_accounts','subscription_plans')
ORDER BY c.relname,t.tgname;

-- section: rules
SELECT tablename,rulename,definition FROM pg_rules WHERE schemaname='public'
 AND tablename IN ('optimization_jobs','credit_transactions','credit_accounts','subscription_plans')
ORDER BY tablename,rulename;

-- section: event_triggers
SELECT evtname,evtevent,evtenabled FROM pg_event_trigger ORDER BY evtname;

-- section: reference_quality
SELECT
 (SELECT count(*) FROM public.optimization_jobs WHERE job_uuid IS NULL OR btrim(job_uuid)='') AS invalid_job_uuid_rows,
 (SELECT count(*) FROM (SELECT job_uuid FROM public.optimization_jobs
   WHERE job_uuid IS NOT NULL GROUP BY job_uuid HAVING count(*)>1) d) AS duplicate_job_uuid_groups,
 (SELECT count(*) FROM public.credit_transactions WHERE type='USAGE' AND (reference IS NULL OR btrim(reference)='')) AS invalid_usage_reference_rows,
 (SELECT count(*) FROM (SELECT reference FROM public.credit_transactions WHERE type='USAGE' AND reference IS NOT NULL
   GROUP BY reference HAVING count(*)>1) d) AS duplicate_usage_reference_groups;

-- section: ledger_links
SELECT tx.type,count(*) AS transactions,
 count(*) FILTER(WHERE tx.type='USAGE' AND j.id IS NULL) AS usage_without_job,
 count(*) FILTER(WHERE tx.type='USAGE' AND j.id IS NOT NULL
   AND (j.company_id IS NULL OR j.company_id::text IS DISTINCT FROM ca.company_id::text)) AS usage_company_mismatch,
 count(*) FILTER(WHERE tx.type='USAGE' AND tx.refunded) AS refunded_usage
FROM public.credit_transactions tx
LEFT JOIN public.credit_accounts ca ON ca.id=tx.credit_account_id
LEFT JOIN public.optimization_jobs j ON j.job_uuid=tx.reference
GROUP BY tx.type ORDER BY tx.type;

-- section: active_jobs
SELECT j.status,count(*) AS jobs,
 count(*) FILTER(WHERE usage.total=0) AS without_usage,
 count(*) FILTER(WHERE usage.total>1) AS multiple_usage,
 count(*) FILTER(WHERE usage.authorized=1 AND usage.total=1) AS exactly_one_unrefunded_company_usage,
 count(*) FILTER(WHERE j.company_id IS NULL OR j.subscription_tier IS NULL OR j.algorithm_tier IS NULL) AS missing_snapshot
FROM public.optimization_jobs j
CROSS JOIN LATERAL (
 SELECT count(*) AS total,count(*) FILTER(WHERE tx.refunded=false
   AND j.company_id IS NOT NULL AND ca.company_id::text=j.company_id::text) AS authorized
 FROM public.credit_transactions tx JOIN public.credit_accounts ca ON ca.id=tx.credit_account_id
 WHERE tx.reference=j.job_uuid AND tx.type='USAGE'
) usage
WHERE j.status IN ('PENDING','RUNNING') GROUP BY j.status ORDER BY j.status;

-- section: catalog
SELECT plan_code,tier,billing_cycle,monthly_credits,algorithm_tier,active
FROM public.subscription_plans
WHERE tier IN ('BASIC','PRO','ULTIMATE') OR plan_code IN ('BASIC_MONTHLY','PRO_MONTHLY','ULTIMATE_MONTHLY')
ORDER BY plan_code;

-- section: flyway_history_presence
SELECT to_regclass('public.flyway_schema_history') IS NOT NULL AS history_table_exists;

COMMIT;
