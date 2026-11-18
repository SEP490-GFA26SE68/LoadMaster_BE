-- Mỗi công ty tự đặt lương cứng, % hoa hồng, phí bốc dỡ và % tài xế hưởng bốc dỡ: không có giá trị mặc định chung.
-- V44 từng đặt mặc định 15 / 150 / 85 chỉ để thêm được cột NOT NULL vào bảng đã có dữ liệu; V45 đã xóa các mức lương cũ.
ALTER TABLE company_payroll_rates ALTER COLUMN commission_percent DROP DEFAULT;
ALTER TABLE company_payroll_rates ALTER COLUMN handling_fee_vnd_per_kg DROP DEFAULT;
ALTER TABLE company_payroll_rates ALTER COLUMN handling_driver_percent DROP DEFAULT;
