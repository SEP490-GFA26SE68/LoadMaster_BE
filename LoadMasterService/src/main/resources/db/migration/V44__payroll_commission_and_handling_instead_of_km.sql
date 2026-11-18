-- Lương tài xế: hoa hồng % trên cước theo kg + phần bốc dỡ theo kg; bỏ tính tiền theo km.
-- Không xóa cột km (DB dùng chung, bản backend cũ vẫn có thể đang chạy): chỉ cho chúng mặc định 0 để code mới không cần ghi.
ALTER TABLE company_payroll_rates
    ADD COLUMN IF NOT EXISTS commission_percent NUMERIC(5, 2) NOT NULL DEFAULT 15 CHECK (commission_percent BETWEEN 0 AND 100),
    ADD COLUMN IF NOT EXISTS handling_fee_vnd_per_kg NUMERIC(15, 2) NOT NULL DEFAULT 150 CHECK (handling_fee_vnd_per_kg >= 0),
    ADD COLUMN IF NOT EXISTS handling_driver_percent NUMERIC(5, 2) NOT NULL DEFAULT 85 CHECK (handling_driver_percent BETWEEN 0 AND 100);
ALTER TABLE company_payroll_rates ALTER COLUMN rate_vnd_per_km SET DEFAULT 0;

ALTER TABLE driver_payrolls
    ADD COLUMN IF NOT EXISTS handling_amount_vnd NUMERIC(15, 0) NOT NULL DEFAULT 0 CHECK (handling_amount_vnd >= 0);
ALTER TABLE driver_payrolls ALTER COLUMN total_km SET DEFAULT 0;
ALTER TABLE driver_payrolls ALTER COLUMN km_amount_vnd SET DEFAULT 0;

ALTER TABLE driver_payroll_trip_lines
    ADD COLUMN IF NOT EXISTS handling_amount_vnd NUMERIC(15, 0) NOT NULL DEFAULT 0 CHECK (handling_amount_vnd >= 0);
ALTER TABLE driver_payroll_trip_lines ALTER COLUMN plan_km SET DEFAULT 0;
ALTER TABLE driver_payroll_trip_lines ALTER COLUMN allocated_km SET DEFAULT 0;
ALTER TABLE driver_payroll_trip_lines ALTER COLUMN rate_vnd_per_km SET DEFAULT 0;
ALTER TABLE driver_payroll_trip_lines ALTER COLUMN km_amount_vnd SET DEFAULT 0;
-- Tuyến không còn ảnh hưởng tiền lương nên không bắt buộc có route plan.
ALTER TABLE driver_payroll_trip_lines ALTER COLUMN route_plan_id DROP NOT NULL;
