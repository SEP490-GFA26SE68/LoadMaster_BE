-- XEM TRƯỚC (chỉ đọc) cho 2026-11-create-missing-credit-accounts.sql. Không ghi gì, chạy bao nhiêu lần cũng được.
--
-- Trước bản sửa này, tài khoản credit chỉ được tạo ở lượt cấp credit tháng, mà lượt đó chỉ chạy khi gói có
-- monthly_credits > 0. Kích hoạt, gán (hợp đồng) hay mở lại một gói không ghi credit tháng (ví dụ Ultimate không
-- giới hạn) để lại công ty có gói ACTIVE nhưng không có dòng credit_accounts.

-- 0. credit_accounts phải có khoá duy nhất trên company_id (V4 tạo UNIQUE; ddl-auto tạo uk_company_credit_account).
--    Không có dòng nào ở đây thì KHÔNG chạy file sửa.
SELECT c.conname, pg_get_constraintdef(c.oid) AS definition
FROM pg_constraint c
WHERE c.conrelid = 'credit_accounts'::regclass
  AND c.contype IN ('u', 'p')
  AND pg_get_constraintdef(c.oid) ILIKE '%(company_id)%';

-- 1. Các công ty sẽ được tạo tài khoản credit số dư 0.
SELECT s.company_id, co.company_name, s.status, p.name AS plan_name, p.monthly_credits, s.started_at, s.expires_at
FROM company_subscriptions s
JOIN companies co ON co.id = s.company_id
LEFT JOIN subscription_plans p ON p.id = s.plan_id
WHERE s.status = 'ACTIVE'
  AND NOT EXISTS (SELECT 1 FROM credit_accounts a WHERE a.company_id = s.company_id)
ORDER BY s.company_id;

-- 2. Đếm nhanh.
SELECT count(*) AS accounts_to_create
FROM company_subscriptions s
WHERE s.status = 'ACTIVE'
  AND NOT EXISTS (SELECT 1 FROM credit_accounts a WHERE a.company_id = s.company_id);
