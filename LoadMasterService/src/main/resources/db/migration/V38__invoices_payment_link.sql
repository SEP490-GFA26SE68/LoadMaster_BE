-- Hoá đơn của thanh toán qua cổng (gói, gia hạn, nạp credit, đăng ký trả phí): mỗi thanh toán thành công đúng một hoá đơn.
-- invoices do ddl-auto=update tạo trên DB dùng chung; script chạy lại bao nhiêu lần cũng được và bỏ qua khi chưa có bảng.
DO $$
BEGIN
    IF to_regclass('invoices') IS NULL THEN
        RETURN;
    END IF;

    ALTER TABLE invoices ADD COLUMN IF NOT EXISTS payment_transaction_id BIGINT;
    ALTER TABLE invoices ADD COLUMN IF NOT EXISTS plan_id BIGINT;
    ALTER TABLE invoices ADD COLUMN IF NOT EXISTS item_type VARCHAR(50);
    ALTER TABLE invoices ADD COLUMN IF NOT EXISTS credit_quantity INTEGER;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uk_invoices_payment_transaction') THEN
        ALTER TABLE invoices ADD CONSTRAINT uk_invoices_payment_transaction UNIQUE (payment_transaction_id);
    END IF;

    IF to_regclass('payment_transactions') IS NOT NULL
            AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_invoices_payment_transaction') THEN
        ALTER TABLE invoices ADD CONSTRAINT fk_invoices_payment_transaction
            FOREIGN KEY (payment_transaction_id) REFERENCES payment_transactions (id);
    END IF;

    IF to_regclass('subscription_plans') IS NOT NULL
            AND NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_invoices_plan') THEN
        ALTER TABLE invoices ADD CONSTRAINT fk_invoices_plan FOREIGN KEY (plan_id) REFERENCES subscription_plans (id);
    END IF;
END $$;
