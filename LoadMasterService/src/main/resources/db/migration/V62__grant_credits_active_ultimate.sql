-- Cấp credit cho các công ty đang dùng gói Ultimate sau khi Ultimate thôi "không giới hạn" (V61).
-- Trước đây Ultimate không được cấp credit nên số dư có thể là 0: không cấp thì họ bị chặn chạy tối ưu tới kỳ cấp credit sau.
-- Đưa số dư lên đúng mức cấp một tháng của gói (30.000), như lần cấp theo kỳ của backend (đặt lại về mức của gói, không cộng dồn);
-- công ty đã có từ 30.000 trở lên thì giữ nguyên. Mỗi lần cấp ghi một dòng MONTHLY_GRANT với số credit thật sự cộng thêm,
-- tham chiếu 'V62-ULTIMATE-<mã gói đăng ký>' — chạy lại không cấp lần hai (đã có dòng đó thì bỏ qua).
-- Chỉ công ty có gói Ultimate đang ACTIVE và chưa hết hạn. Giờ ghi theo giờ Việt Nam, dạng 'YYYY-MM-DD HH24:MI:SS' như backend.
-- Chạy SAU V61.

-- Công ty Ultimate chưa có tài khoản credit: tạo tài khoản số dư 0 (như CreditService.ensureAccount).
INSERT INTO credit_accounts (company_id, balance, version)
SELECT cs.company_id, 0, 0
FROM company_subscriptions cs
JOIN subscription_plans sp ON sp.id = cs.plan_id
WHERE sp.plan_code = 'ULTIMATE_MONTHLY'
  AND cs.status = 'ACTIVE'
  AND (cs.expires_at IS NULL OR cs.expires_at > to_char(now() AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYYY-MM-DD HH24:MI:SS'))
  AND NOT EXISTS (SELECT 1 FROM credit_accounts ca WHERE ca.company_id = cs.company_id);

WITH targets AS (
    SELECT ca.id AS account_id, cs.id AS subscription_id, ca.balance
    FROM company_subscriptions cs
    JOIN subscription_plans sp ON sp.id = cs.plan_id
    JOIN credit_accounts ca ON ca.company_id = cs.company_id
    WHERE sp.plan_code = 'ULTIMATE_MONTHLY'
      AND cs.status = 'ACTIVE'
      AND (cs.expires_at IS NULL OR cs.expires_at > to_char(now() AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYYY-MM-DD HH24:MI:SS'))
      AND ca.balance < 30000
      AND NOT EXISTS (
          SELECT 1 FROM credit_transactions ct
          WHERE ct.reference = 'V62-ULTIMATE-' || cs.id AND ct.type = 'MONTHLY_GRANT'
      )
),
granted AS (
    UPDATE credit_accounts ca
    SET balance = 30000,
        version = COALESCE(ca.version, 0) + 1
    FROM targets t
    WHERE ca.id = t.account_id
    RETURNING ca.id
)
INSERT INTO credit_transactions (credit_account_id, amount, type, reference, refunded, created_at)
SELECT t.account_id,
       30000 - t.balance,
       'MONTHLY_GRANT',
       'V62-ULTIMATE-' || t.subscription_id,
       FALSE,
       to_char(now() AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYYY-MM-DD HH24:MI:SS')
FROM targets t;
