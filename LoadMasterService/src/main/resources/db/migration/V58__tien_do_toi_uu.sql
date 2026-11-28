-- Tiến độ sống của lần chạy tối ưu xếp hàng. Trước đây web chỉ biết PENDING / RUNNING trong lúc chờ; dịch vụ tối ưu
-- (OptimizeService, job_progress.py) ghi snapshot khoảng mỗi giây khi job đang RUNNING, LoadMasterService chỉ đọc
-- (GET /api/trips/optimization-jobs/{jobId} → progress, elapsedMs). Trống = job chưa báo gì (PENDING, engine từ xa, job cũ).
-- Chỉ THÊM cột (cho phép trống, không mặc định), không đổi cột cũ, không sửa dữ liệu. Chạy lại an toàn (IF NOT EXISTS).
-- Chạy tay trên DB dùng chung: sao lưu, chạy file này, rồi mới deploy LoadMasterService (ddl-auto: validate) và
-- OptimizeService (entity SQLAlchemy đọc / ghi cột này).
--
--   optimization_jobs.progress  JSON: stage (BUILDING | IMPROVING | SELECTING | SAVING), algorithm, layouts_tried,
--                               total_packages, best_placed_count, best_volume_percent (0–100), updated_at (UTC, Z)

ALTER TABLE optimization_jobs
    ADD COLUMN IF NOT EXISTS progress jsonb;
