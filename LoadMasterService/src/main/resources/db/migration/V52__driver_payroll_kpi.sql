-- KPI theo kg cho lương tài xế.
-- Công ty đặt chỉ tiêu kg/tháng và tiền thưởng KPI trong mức lương; hệ thống tự tính tài xế có đạt không.
-- Chỉ thêm cột (có mặc định), không đụng dữ liệu cũ: mức lương và bảng lương đã có coi như không dùng KPI.

ALTER TABLE company_payroll_rates ADD COLUMN IF NOT EXISTS kpi_target_kg NUMERIC(15, 3);
ALTER TABLE company_payroll_rates ADD COLUMN IF NOT EXISTS kpi_bonus_vnd NUMERIC(15, 2) NOT NULL DEFAULT 0;

ALTER TABLE driver_payrolls ADD COLUMN IF NOT EXISTS kpi_achieved BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE driver_payrolls ADD COLUMN IF NOT EXISTS kpi_amount_vnd NUMERIC(15, 0) NOT NULL DEFAULT 0;

-- Không dùng KPI thì tiền thưởng là 0; có chỉ tiêu thì chỉ tiêu và tiền thưởng đều phải lớn hơn 0.
ALTER TABLE company_payroll_rates DROP CONSTRAINT IF EXISTS chk_company_payroll_rates_kpi;
ALTER TABLE company_payroll_rates ADD CONSTRAINT chk_company_payroll_rates_kpi CHECK (
    (kpi_target_kg IS NULL AND kpi_bonus_vnd = 0)
    OR (kpi_target_kg > 0 AND kpi_bonus_vnd > 0)
);
