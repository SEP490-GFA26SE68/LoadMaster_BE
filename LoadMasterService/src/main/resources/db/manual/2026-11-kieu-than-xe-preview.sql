-- XEM TRƯỚC (chỉ đọc) cho db/migration/V59__kieu_than_xe.sql. Không ghi gì, chạy bao nhiêu lần cũng được.
-- Chạy được cả TRƯỚC lẫn SAU V59 (không đọc cột mới).
--
-- V59 thêm vehicle_types.body_style (cho phép trống) và ràng buộc chk_vehicle_types_body_style. Dòng cũ để trống: web tự
-- chọn kiểu thân xe theo kích thước lòng thùng, nên không có dòng nào phải điền.

-- 0. Cột mới đã có chưa (trước V59: không dòng nào).
SELECT table_name, column_name, data_type, character_maximum_length, is_nullable
FROM information_schema.columns
WHERE table_schema = current_schema()
  AND table_name = 'vehicle_types'
  AND column_name = 'body_style';

-- 1. Ràng buộc kiểm giá trị đã có chưa (trước V59: không dòng nào).
SELECT c.conname, pg_get_constraintdef(c.oid) AS definition
FROM pg_constraint c
WHERE c.conrelid = 'vehicle_types'::regclass
  AND c.conname = 'chk_vehicle_types_body_style';

-- 2. Số loại xe, theo công ty. Mọi dòng sẽ có body_style trống sau V59.
SELECT company_id, count(*) AS so_loai_xe
FROM vehicle_types
GROUP BY company_id
ORDER BY company_id;

SELECT count(*) AS tong_loai_xe
FROM vehicle_types;
