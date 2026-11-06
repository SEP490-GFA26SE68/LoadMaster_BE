-- Cấp tracking_barcode cho kiện cũ còn trống (kiện tạo trước khi máy chủ tự cấp mã vạch).
-- CHẠY TAY, MỘT LẦN, sau khi sao lưu. Không đặt trong db/migration để Flyway không tự chạy.
-- Luật giống TrackingBarcodeAllocator: mã vạch = package_code; trùng thì thêm hậu tố -<id>.
-- Chạy lại nhiều lần vô hại: chỉ chạm dòng tracking_barcode còn NULL.

BEGIN;

-- 1. Mã vạch = package_code khi mã đó chưa ai dùng làm mã vạch và không trùng kiện khác cùng lượt.
WITH candidates AS (
    SELECT p.id,
           btrim(p.package_code) AS code,
           row_number() OVER (PARTITION BY upper(btrim(p.package_code)) ORDER BY p.id) AS rn
    FROM packages p
    WHERE p.tracking_barcode IS NULL
      AND p.package_code IS NOT NULL
      AND btrim(p.package_code) <> ''
)
UPDATE packages p
SET tracking_barcode = left(c.code, 100)
FROM candidates c
WHERE p.id = c.id
  AND c.rn = 1
  AND NOT EXISTS (
      SELECT 1 FROM packages o
      WHERE upper(o.tracking_barcode) = upper(left(c.code, 100))
  );

-- 2. Còn trống (mã trùng hoặc kiện không có mã): package_code-<id>, hoặc PKG-<id>.
UPDATE packages p
SET tracking_barcode = CASE
        WHEN p.package_code IS NULL OR btrim(p.package_code) = '' THEN 'PKG-' || p.id
        ELSE left(btrim(p.package_code), 100 - length('-' || p.id)) || '-' || p.id
    END
WHERE p.tracking_barcode IS NULL;

-- Kiểm: phải trả 0 dòng.
SELECT upper(tracking_barcode), count(*)
FROM packages
WHERE tracking_barcode IS NOT NULL
GROUP BY upper(tracking_barcode)
HAVING count(*) > 1;

COMMIT;
