-- PROPOSED / MANUAL ONLY. Never run against a shared database without approved maintenance/backup.
-- Scoped to the reviewed public schema. All Phase 2 columns already exist on the inspected dev DB.
-- No column ALTER/ADD, backfill, ledger/account/subscription DML, or timestamp conversion.
BEGIN;
SET LOCAL lock_timeout='5s';
SET LOCAL statement_timeout='60s';
SET LOCAL search_path=pg_catalog,public;
-- Stop/drain writers first. These short, ordinary index builds are deliberately transactional.
LOCK TABLE public.optimization_jobs, public.credit_transactions IN SHARE ROW EXCLUSIVE MODE;
DO $migration$
DECLARE required record; uuid_att smallint; ref_att smallint; found_index boolean;
BEGIN
 FOR required IN SELECT * FROM (VALUES
  ('optimization_jobs','job_uuid','character varying(64)'),
  ('optimization_jobs','company_id','character varying(64)'),
  ('optimization_jobs','subscription_tier','character varying(50)'),
  ('optimization_jobs','algorithm_tier','character varying(50)'),
  ('optimization_jobs','parent_plan_id','bigint'),('optimization_jobs','seed','integer'),
  ('credit_transactions','reference','character varying(100)'),
  ('credit_transactions','type','character varying(20)'),('credit_transactions','refunded','boolean')
 ) AS expected(table_name,column_name,column_type) LOOP
  IF NOT EXISTS(SELECT 1 FROM pg_attribute a
    WHERE a.attrelid=to_regclass('public.'||required.table_name) AND a.attname=required.column_name
      AND NOT a.attisdropped AND format_type(a.atttypid,a.atttypmod)=required.column_type) THEN
   RAISE EXCEPTION 'Missing/incompatible %.%; stop and prepare a separately reviewed column change',
      required.table_name,required.column_name;
  END IF;
 END LOOP;
 IF EXISTS(SELECT 1 FROM public.optimization_jobs
   WHERE job_uuid IS NULL OR btrim(job_uuid)='') OR EXISTS(
   SELECT 1 FROM public.optimization_jobs GROUP BY job_uuid HAVING count(*)>1) THEN
  RAISE EXCEPTION 'Invalid/duplicate job UUIDs; reconcile manually, no automatic backfill';
 END IF;
 IF EXISTS(SELECT 1 FROM public.credit_transactions WHERE type='USAGE'
   AND (reference IS NULL OR btrim(reference)='')) OR EXISTS(
   SELECT 1 FROM public.credit_transactions WHERE type='USAGE' GROUP BY reference HAVING count(*)>1) THEN
  RAISE EXCEPTION 'Invalid/duplicate USAGE references; reconcile manually, never delete ledger to pass';
 END IF;
 SELECT attnum INTO uuid_att FROM pg_attribute
 WHERE attrelid='public.optimization_jobs'::regclass AND attname='job_uuid';
 SELECT EXISTS(SELECT 1 FROM pg_index i WHERE i.indrelid='public.optimization_jobs'::regclass
   AND i.indisunique AND i.indisvalid AND i.indisready AND i.indimmediate
   AND i.indnkeyatts=1 AND i.indkey[0]=uuid_att AND i.indexprs IS NULL AND i.indpred IS NULL)
 INTO found_index;
 IF NOT found_index THEN
  IF to_regclass('public.uk_phase2_job_uuid') IS NOT NULL THEN
   RAISE EXCEPTION 'uk_phase2_job_uuid exists but is not the required valid unique index';
  END IF;
  CREATE UNIQUE INDEX uk_phase2_job_uuid ON public.optimization_jobs(job_uuid);
 END IF;

 SELECT attnum INTO ref_att FROM pg_attribute
 WHERE attrelid='public.credit_transactions'::regclass AND attname='reference';
 -- Recognize the equivalent single-key partial unique index regardless of its name.
 SELECT EXISTS(SELECT 1 FROM pg_index i WHERE i.indrelid='public.credit_transactions'::regclass
   AND i.indisunique AND i.indisvalid AND i.indisready AND i.indimmediate
   AND i.indnkeyatts=1 AND i.indkey[0]=ref_att AND i.indexprs IS NULL
   AND regexp_replace(regexp_replace(pg_get_expr(i.indpred,i.indrelid),
       '::(text|character varying)','','g'),'[()"[:space:]]','','g')='type=''USAGE''')
 INTO found_index;
 IF NOT found_index THEN
  IF to_regclass('public.uk_phase2_credit_usage_reference') IS NOT NULL THEN
   RAISE EXCEPTION 'uk_phase2_credit_usage_reference exists but is not the required valid partial unique index';
  END IF;
  CREATE UNIQUE INDEX uk_phase2_credit_usage_reference
    ON public.credit_transactions(reference) WHERE type='USAGE';
 END IF;
END $migration$;
COMMIT;
