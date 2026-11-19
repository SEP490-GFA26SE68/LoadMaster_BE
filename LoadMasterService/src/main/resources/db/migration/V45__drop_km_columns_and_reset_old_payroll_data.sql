-- Công thức lương mới (hoa hồng % trên cước theo kg + phần bốc dỡ theo kg, bỏ tính theo km).
-- 1) Xóa dữ liệu lương đã tính theo công thức cũ: bảng lương (kèm chi tiết từng tài xế, từng chuyến - xóa theo ON DELETE CASCADE)
--    và các phiên bản mức lương cũ (đơn giá/kg cũ là tiền trả thẳng cho tài xế, nghĩa đã đổi thành cước thu khách).
--    Công ty nhập lại mức lương theo công thức mới và bấm tính lại bảng lương.
--    GIỮ: lương cứng riêng từng tài xế (driver_base_salary_overrides) và phân công tài xế theo chuyến (trip_payroll_driver_shares).
-- Xóa từ bảng con lên bảng cha: khóa ngoại thực tế trên DB (do Hibernate tạo) không có ON DELETE CASCADE.
DELETE FROM driver_payroll_trip_lines;
DELETE FROM driver_payrolls;
DELETE FROM payroll_periods;
DELETE FROM company_payroll_rates;

-- 2) Bỏ hẳn các cột km không còn dùng.
ALTER TABLE company_payroll_rates DROP COLUMN IF EXISTS rate_vnd_per_km;

ALTER TABLE driver_payrolls DROP COLUMN IF EXISTS total_km;
ALTER TABLE driver_payrolls DROP COLUMN IF EXISTS km_amount_vnd;

ALTER TABLE driver_payroll_trip_lines DROP COLUMN IF EXISTS plan_km;
ALTER TABLE driver_payroll_trip_lines DROP COLUMN IF EXISTS allocated_km;
ALTER TABLE driver_payroll_trip_lines DROP COLUMN IF EXISTS rate_vnd_per_km;
ALTER TABLE driver_payroll_trip_lines DROP COLUMN IF EXISTS km_amount_vnd;
