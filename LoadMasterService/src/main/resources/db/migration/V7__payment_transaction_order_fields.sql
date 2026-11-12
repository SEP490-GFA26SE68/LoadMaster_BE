-- Align payment_transactions with PaymentTransaction entity (order_type, target_id)

ALTER TABLE IF EXISTS payment_transactions
    ADD COLUMN IF NOT EXISTS order_type VARCHAR(50);

ALTER TABLE IF EXISTS payment_transactions
    ADD COLUMN IF NOT EXISTS target_id BIGINT;
