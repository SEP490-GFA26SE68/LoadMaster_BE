-- Hai cột của packages trước đây chỉ có nhờ ddl-auto=update: is_pinned (ghim kiện) và qr_token (mã QR, đã mã hoá
-- bằng AES ở tầng ứng dụng). Migration này khớp chúng với entity CargoPackage và chạy lại bao nhiêu lần cũng được.
-- qr_token không thể sinh bằng SQL (giá trị phải là bản mã của ứng dụng): còn dòng thiếu mã thì giữ cột cho phép
-- null và chỉ báo WARNING, để không làm hỏng lần nâng cấp; ứng dụng vẫn cấp mã cho mọi kiện mới.
DO $$
DECLARE
    has_unique_token BOOLEAN;
    null_tokens BIGINT;
    duplicate_tokens BIGINT;
BEGIN
    IF to_regclass('packages') IS NULL THEN
        RETURN;
    END IF;

    ALTER TABLE packages ADD COLUMN IF NOT EXISTS is_pinned BOOLEAN NOT NULL DEFAULT FALSE;
    UPDATE packages SET is_pinned = FALSE WHERE is_pinned IS NULL;
    ALTER TABLE packages ALTER COLUMN is_pinned SET DEFAULT FALSE;
    ALTER TABLE packages ALTER COLUMN is_pinned SET NOT NULL;

    ALTER TABLE packages ADD COLUMN IF NOT EXISTS qr_token VARCHAR(128);

    SELECT EXISTS (
        SELECT 1
        FROM pg_index i
        JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = i.indkey[0]
        WHERE i.indrelid = 'packages'::regclass
          AND i.indisunique
          AND i.indnatts = 1
          AND a.attname = 'qr_token'
    ) INTO has_unique_token;

    IF NOT has_unique_token THEN
        SELECT count(*) INTO duplicate_tokens
        FROM (SELECT qr_token FROM packages WHERE qr_token IS NOT NULL GROUP BY qr_token HAVING count(*) > 1) d;
        IF duplicate_tokens = 0 THEN
            CREATE UNIQUE INDEX IF NOT EXISTS uk_packages_qr_token ON packages (qr_token);
        ELSE
            RAISE WARNING 'packages.qr_token has % duplicated values; unique index not created', duplicate_tokens;
        END IF;
    END IF;

    SELECT count(*) INTO null_tokens FROM packages WHERE qr_token IS NULL;
    IF null_tokens = 0 THEN
        ALTER TABLE packages ALTER COLUMN qr_token SET NOT NULL;
    ELSE
        RAISE WARNING 'packages.qr_token is missing on % rows; column left nullable', null_tokens;
    END IF;
END $$;
