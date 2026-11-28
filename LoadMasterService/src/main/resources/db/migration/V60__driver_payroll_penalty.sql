-- Tiền phạt trong lương tài xế: quản lý công ty nhập tay cho TỪNG tài xế, từng tháng (không phải mức chung của công ty).
-- Tổng lương = lương cứng + hoa hồng + bốc dỡ + thưởng KPI - tiền phạt; không bao giờ âm.
-- Có phạt thì bắt buộc ghi lý do (tối đa 200 ký tự) để tài xế thấy vì sao bị trừ.
-- Chỉ thêm cột có mặc định, không đụng dữ liệu cũ: các dòng lương đã có coi như không bị phạt.

ALTER TABLE driver_payrolls ADD COLUMN IF NOT EXISTS penalty_vnd NUMERIC(15, 0) NOT NULL DEFAULT 0;
ALTER TABLE driver_payrolls ADD COLUMN IF NOT EXISTS penalty_note VARCHAR(200);

-- Không phạt thì không có lý do; có phạt thì phải có lý do không rỗng.
ALTER TABLE driver_payrolls DROP CONSTRAINT IF EXISTS chk_driver_payrolls_penalty;
ALTER TABLE driver_payrolls ADD CONSTRAINT chk_driver_payrolls_penalty CHECK (
    (penalty_vnd = 0 AND penalty_note IS NULL)
    OR (penalty_vnd > 0 AND penalty_note IS NOT NULL AND length(btrim(penalty_note)) > 0)
);
