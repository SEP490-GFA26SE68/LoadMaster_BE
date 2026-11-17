-- READ ONLY assertions after 02/03. Catalog is reported separately because 04 needs business approval.
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL statement_timeout='30s';
SET LOCAL lock_timeout='3s';
DO $verify$
DECLARE definition text;
BEGIN
 IF NOT EXISTS(SELECT 1 FROM pg_index i JOIN pg_attribute a
   ON a.attrelid=i.indrelid AND a.attname='job_uuid'
   WHERE i.indrelid='public.optimization_jobs'::regclass AND i.indisunique AND i.indisvalid
     AND i.indisready AND i.indimmediate AND i.indnkeyatts=1 AND i.indkey[0]=a.attnum
     AND i.indexprs IS NULL AND i.indpred IS NULL) THEN
  RAISE EXCEPTION 'Missing valid unique job UUID index';
 END IF;
 IF NOT EXISTS(SELECT 1 FROM pg_index i JOIN pg_attribute a
   ON a.attrelid=i.indrelid AND a.attname='reference'
   WHERE i.indrelid='public.credit_transactions'::regclass AND i.indisunique AND i.indisvalid
     AND i.indisready AND i.indimmediate AND i.indnkeyatts=1 AND i.indkey[0]=a.attnum
     AND i.indexprs IS NULL AND regexp_replace(regexp_replace(pg_get_expr(i.indpred,i.indrelid),
       '::(text|character varying)','','g'),'[()"[:space:]]','','g')='type=''USAGE''') THEN
  RAISE EXCEPTION 'Missing valid partial unique USAGE reference index';
 END IF;
 SELECT pg_get_constraintdef(oid) INTO definition FROM pg_constraint
 WHERE conrelid='public.optimization_jobs'::regclass
   AND conname='optimization_jobs_algorithm_name_check' AND contype='c' AND convalidated;
 IF definition IS NULL OR regexp_replace(regexp_replace(definition,$casts$::(character varying|text)(\[\])?$casts$,'','g'),'[()[:space:]]','','g') IS DISTINCT FROM
   regexp_replace(regexp_replace($expected$CHECK (((algorithm_name IS NULL) OR ((algorithm_name)::text = ANY ((ARRAY['EP_DBLF'::character varying, 'EP_DBLF_GA'::character varying, 'EP_DBLF_GA_AI'::character varying, 'GENETIC_ALGORITHM'::character varying])::text[]))))$expected$,$casts$::(character varying|text)(\[\])?$casts$,'','g'),'[()[:space:]]','','g') THEN
  RAISE EXCEPTION 'Algorithm CHECK is not the reviewed validated target';
 END IF;
 IF EXISTS(SELECT 1 FROM public.credit_transactions WHERE type='USAGE'
   AND (reference IS NULL OR btrim(reference)='')) THEN
  RAISE EXCEPTION 'Invalid USAGE references still require manual review';
 END IF;
END $verify$;
SELECT 'schema_checks_passed' AS result;
-- catalog-verification:
WITH expected(plan_code,tier,monthly_credits,algorithm_tier) AS (VALUES
 ('BASIC_MONTHLY','BASIC',3000,'EP_DBLF'),('PRO_MONTHLY','PRO',15000,'EP_DBLF_GA'),
 ('ULTIMATE_MONTHLY','ULTIMATE',30000,'EP_DBLF_GA_AI'))
SELECT e.plan_code,p.tier,p.billing_cycle,p.monthly_credits,p.algorithm_tier,p.active,
 p.id IS NOT NULL AS catalog_row_exists,
 COALESCE(p.tier=e.tier AND p.billing_cycle='MONTHLY' AND p.monthly_credits=e.monthly_credits
   AND p.algorithm_tier=e.algorithm_tier AND p.active=true,false) AS matches_agreed_catalog
FROM expected e LEFT JOIN public.subscription_plans p ON p.plan_code=e.plan_code
ORDER BY e.plan_code;
SELECT status,count(*) AS jobs FROM public.optimization_jobs
WHERE status IN ('PENDING','RUNNING') GROUP BY status ORDER BY status;
-- Also re-run 01-preflight.sql and compare every unrelated constraint, column, index and aggregate.
COMMIT;
