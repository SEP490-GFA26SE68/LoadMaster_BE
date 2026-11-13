-- Lý do của job tối ưu FAILED khi máy chủ tự kết thúc job (JOB_INTERRUPTED: dịch vụ tối ưu dừng giữa chừng).
-- Cột thêm mới, cho phép NULL; dữ liệu cũ không đổi.
ALTER TABLE IF EXISTS optimization_jobs ADD COLUMN IF NOT EXISTS failure_code VARCHAR(64);
