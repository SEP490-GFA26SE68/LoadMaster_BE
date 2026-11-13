-- SỬA TAY MỘT LẦN — KHÔNG phải migration (Flyway chỉ quét db/migration). Chạy bằng tay, sau khi đã chạy
-- 2026-11-create-missing-credit-accounts-preview.sql và đọc kết quả. Sao lưu DB trước.
--
-- Việc: tạo tài khoản credit số dư 0 cho mọi công ty có gói ACTIVE mà chưa có dòng credit_accounts (xem preview).
-- Không đổi số dư của tài khoản đã có, không cấp credit.
--
-- An toàn khi chạy lại và khi máy chủ đang chạy: ON CONFLICT DO NOTHING trên khoá duy nhất company_id, nên dòng
-- máy chủ vừa tạo cùng lúc hay đã tạo ở lần chạy trước không bị chèn lại, không lỗi. Thiếu khoá duy nhất đó thì
-- dừng, không chèn gì (chèn khi không có khoá có thể tạo hai dòng cho một công ty).
--
-- Trả về (nếu cần): các dòng tạo ở đây có balance = 0 và chưa có giao dịch; xoá theo danh sách công ty ở preview:
--   DELETE FROM credit_accounts a WHERE a.company_id IN (...) AND a.balance = 0
--     AND NOT EXISTS (SELECT 1 FROM credit_transactions t WHERE t.credit_account_id = a.id);

BEGIN;

DO $$
DECLARE
    created INTEGER;
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint c
        WHERE c.conrelid = 'credit_accounts'::regclass
          AND c.contype IN ('u', 'p')
          AND pg_get_constraintdef(c.oid) ILIKE '%(company_id)%'
    ) THEN
        RAISE EXCEPTION 'credit_accounts chưa có khoá duy nhất trên company_id: không chèn gì';
    END IF;

    INSERT INTO credit_accounts (company_id, balance, version)
    SELECT DISTINCT s.company_id, 0, 0
    FROM company_subscriptions s
    WHERE s.status = 'ACTIVE'
      AND NOT EXISTS (SELECT 1 FROM credit_accounts a WHERE a.company_id = s.company_id)
    ON CONFLICT DO NOTHING;
    GET DIAGNOSTICS created = ROW_COUNT;

    RAISE NOTICE 'Đã tạo % tài khoản credit', created;
END $$;

COMMIT;
