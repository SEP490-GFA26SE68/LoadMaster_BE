-- Separate business approval required: choose the effective purchasing/renewal cycle first.
-- Changes ONE catalog field only. Never grants credits, changes balances or activates subscriptions.
BEGIN;
SET LOCAL lock_timeout='5s';
SET LOCAL statement_timeout='30s';
LOCK TABLE public.subscription_plans IN SHARE ROW EXCLUSIVE MODE;
SELECT plan_code,tier,billing_cycle,algorithm_tier,monthly_credits,active
FROM public.subscription_plans WHERE plan_code='PRO_MONTHLY';
DO $catalog$
DECLARE plan record; affected integer;
BEGIN
 IF EXISTS(SELECT 1 FROM pg_trigger WHERE tgrelid='public.subscription_plans'::regclass
    AND NOT tgisinternal AND tgenabled<>'D') OR EXISTS(
    SELECT 1 FROM pg_rewrite WHERE ev_class='public.subscription_plans'::regclass AND rulename<>'_RETURN') THEN
  RAISE EXCEPTION 'Custom catalog trigger/rule exists; review side effects before any catalog update';
 END IF;
 IF (SELECT count(*) FROM public.subscription_plans WHERE plan_code='PRO_MONTHLY')<>1 THEN
  RAISE EXCEPTION 'Expected exactly one PRO_MONTHLY catalog row; stop without inserting a replacement';
 END IF;
 SELECT * INTO plan FROM public.subscription_plans WHERE plan_code='PRO_MONTHLY';
 IF plan.tier IS DISTINCT FROM 'PRO' OR plan.billing_cycle IS DISTINCT FROM 'MONTHLY'
    OR plan.algorithm_tier IS DISTINCT FROM 'EP_DBLF_GA' OR plan.active IS DISTINCT FROM true THEN
  RAISE EXCEPTION 'Unexpected PRO identity/cycle/algorithm/active flag; do not alter or activate automatically';
 END IF;
 IF plan.monthly_credits=15000 THEN
  RAISE NOTICE 'PRO_MONTHLY is already 15000; no catalog update';
 ELSIF plan.monthly_credits=10000 THEN
  UPDATE public.subscription_plans SET monthly_credits=15000
  WHERE plan_code='PRO_MONTHLY' AND tier='PRO' AND billing_cycle='MONTHLY'
    AND algorithm_tier='EP_DBLF_GA' AND active=true AND monthly_credits=10000;
  GET DIAGNOSTICS affected=ROW_COUNT;
  IF affected<>1 THEN RAISE EXCEPTION 'Catalog update did not match exactly one row'; END IF;
 ELSE
  RAISE EXCEPTION 'Unexpected PRO monthly_credits; only reviewed 10000 -> 15000 is permitted';
 END IF;
END $catalog$;
SELECT plan_code,tier,billing_cycle,algorithm_tier,monthly_credits,active
FROM public.subscription_plans WHERE plan_code='PRO_MONTHLY';
COMMIT;
