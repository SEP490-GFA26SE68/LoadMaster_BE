-- Chỉ mục cho các truy vấn lọc theo công ty / theo chuyến / theo job; chỉ thêm, không đổi dữ liệu.
CREATE INDEX IF NOT EXISTS idx_trips_company_id ON trips (company_id);
CREATE INDEX IF NOT EXISTS idx_packages_company_id ON packages (company_id);
CREATE INDEX IF NOT EXISTS idx_optimization_jobs_trip_id ON optimization_jobs (trip_id);
CREATE INDEX IF NOT EXISTS idx_load_plans_job_id ON load_plans (job_id);
CREATE INDEX IF NOT EXISTS idx_audit_logs_company_created ON audit_logs (company_id, created_at);

-- Mỗi tham chiếu chỉ ghi một lần cho mỗi loại giao dịch credit (chặn trừ, hoàn, nạp, cấp theo kỳ hai lần).
-- Đã kiểm DB dùng chung trước khi thêm: không có cặp (reference, type) trùng. Giá trị null không bị ràng buộc.
CREATE UNIQUE INDEX IF NOT EXISTS uk_credit_tx_reference_type ON credit_transactions (reference, type);
