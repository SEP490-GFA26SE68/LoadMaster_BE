-- Format payment_transactions.created_at and payment_transactions.updated_at as 'yyyy-MM-dd HH:mm:ss' (varchar(19)),
-- enforce NOT NULL + format + chronological CHECK constraints, and protect rows from manual tampering via trigger.

DROP TRIGGER IF EXISTS trg_protect_payment_transactions ON public.payment_transactions;
DROP FUNCTION IF EXISTS public.fn_protect_payment_transactions();

ALTER TABLE public.payment_transactions DROP CONSTRAINT IF EXISTS payment_transactions_created_at_check;
ALTER TABLE public.payment_transactions DROP CONSTRAINT IF EXISTS payment_transactions_updated_at_check;
ALTER TABLE public.payment_transactions DROP CONSTRAINT IF EXISTS chk_payment_transactions_updated_after_created;

ALTER TABLE public.payment_transactions ALTER COLUMN created_at DROP DEFAULT;
ALTER TABLE public.payment_transactions ALTER COLUMN updated_at DROP DEFAULT;

ALTER TABLE public.payment_transactions
    ALTER COLUMN created_at TYPE varchar(19)
    USING CASE
        WHEN created_at IS NULL THEN to_char(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYYY-MM-DD HH24:MI:SS')
        ELSE substring(replace(created_at::text, 'T', ' ') from 1 for 19)
    END;

ALTER TABLE public.payment_transactions
    ALTER COLUMN updated_at TYPE varchar(19)
    USING CASE
        WHEN updated_at IS NULL THEN NULL
        ELSE substring(replace(updated_at::text, 'T', ' ') from 1 for 19)
    END;

UPDATE public.payment_transactions
SET created_at = to_char(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYYY-MM-DD HH24:MI:SS')
WHERE created_at IS NULL
   OR created_at !~ '^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01]) ([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]$';

UPDATE public.payment_transactions
SET updated_at = created_at
WHERE updated_at IS NULL
   OR updated_at !~ '^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01]) ([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]$'
   OR updated_at < created_at;

ALTER TABLE public.payment_transactions
    ALTER COLUMN created_at SET NOT NULL,
    ALTER COLUMN created_at SET DEFAULT to_char(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYYY-MM-DD HH24:MI:SS'),
    ALTER COLUMN updated_at SET NOT NULL,
    ALTER COLUMN updated_at SET DEFAULT to_char(CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYYY-MM-DD HH24:MI:SS');

ALTER TABLE public.payment_transactions
    ADD CONSTRAINT payment_transactions_created_at_check
    CHECK (created_at ~ '^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01]) ([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]$');

ALTER TABLE public.payment_transactions
    ADD CONSTRAINT payment_transactions_updated_at_check
    CHECK (updated_at ~ '^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01]) ([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]$');

ALTER TABLE public.payment_transactions
    ADD CONSTRAINT chk_payment_transactions_updated_after_created
    CHECK (updated_at >= created_at);

CREATE OR REPLACE FUNCTION public.fn_protect_payment_transactions()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'payment_transactions records are immutable and cannot be deleted';
    END IF;

    IF OLD.status IN ('SUCCESS', 'FAILED') THEN
        RAISE EXCEPTION 'Completed payment_transactions (status=%) cannot be modified', OLD.status;
    END IF;

    IF NEW.created_at IS DISTINCT FROM OLD.created_at
       OR NEW.company_id IS DISTINCT FROM OLD.company_id
       OR NEW.gateway IS DISTINCT FROM OLD.gateway
       OR NEW.gateway_transaction_id IS DISTINCT FROM OLD.gateway_transaction_id
       OR NEW.amount_vnd IS DISTINCT FROM OLD.amount_vnd
       OR NEW.order_type IS DISTINCT FROM OLD.order_type
       OR NEW.target_id IS DISTINCT FROM OLD.target_id THEN
        RAISE EXCEPTION 'Core fields (created_at, company_id, gateway, gateway_transaction_id, amount_vnd, order_type, target_id) of payment_transactions cannot be modified';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_protect_payment_transactions
BEFORE UPDATE OR DELETE ON public.payment_transactions
FOR EACH ROW
EXECUTE FUNCTION public.fn_protect_payment_transactions();

