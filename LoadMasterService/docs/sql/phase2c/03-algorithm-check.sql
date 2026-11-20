-- Separate from credit migration: only widens the reviewed enum CHECK; never dispatches an algorithm.
-- Legacy GENETIC_ALGORITHM and SQL NULL remain accepted. Unknown custom constraints abort.
BEGIN;
SET LOCAL lock_timeout='5s';
SET LOCAL statement_timeout='60s';
LOCK TABLE public.optimization_jobs IN ACCESS EXCLUSIVE MODE;
DO $migration$
DECLARE definition text; normalized text; old_definition text; new_definition text;
BEGIN
 SELECT pg_get_constraintdef(oid) INTO definition FROM pg_constraint
 WHERE conrelid='public.optimization_jobs'::regclass
   AND conname='optimization_jobs_algorithm_name_check' AND contype='c';
 old_definition=$old$CHECK (((algorithm_name)::text = ANY ((ARRAY['EP_DBLF'::character varying, 'GENETIC_ALGORITHM'::character varying])::text[])))$old$;
 new_definition=$new$CHECK (((algorithm_name IS NULL) OR ((algorithm_name)::text = ANY ((ARRAY['EP_DBLF'::character varying, 'EP_DBLF_GA'::character varying, 'EP_DBLF_GA_AI'::character varying, 'GENETIC_ALGORITHM'::character varying])::text[]))))$new$;
 -- PG deparsers may put the text cast on the array or on each literal. Both reviewed forms
 -- express exactly the same whitelist; keep every logical token/name, strip only these casts/formatting.
 normalized=regexp_replace(regexp_replace(definition,$casts$::(character varying|text)(\[\])?$casts$,'','g'),'[()[:space:]]','','g');
 IF normalized=regexp_replace(regexp_replace(new_definition,$casts$::(character varying|text)(\[\])?$casts$,'','g'),'[()[:space:]]','','g') THEN
  -- Re-run: do not drop/recreate an already correct CHECK, but ensure it is validated.
  ALTER TABLE public.optimization_jobs VALIDATE CONSTRAINT optimization_jobs_algorithm_name_check;
 ELSIF normalized=regexp_replace(regexp_replace(old_definition,$casts$::(character varying|text)(\[\])?$casts$,'','g'),'[()[:space:]]','','g') THEN
  ALTER TABLE public.optimization_jobs DROP CONSTRAINT optimization_jobs_algorithm_name_check;
  ALTER TABLE public.optimization_jobs ADD CONSTRAINT optimization_jobs_algorithm_name_check CHECK
   (algorithm_name IS NULL OR algorithm_name IN ('EP_DBLF','EP_DBLF_GA','EP_DBLF_GA_AI','GENETIC_ALGORITHM'));
 ELSE
  RAISE EXCEPTION 'Missing/unreviewed algorithm CHECK; preserve and inspect manually: %',definition;
 END IF;
END $migration$;
COMMIT;
