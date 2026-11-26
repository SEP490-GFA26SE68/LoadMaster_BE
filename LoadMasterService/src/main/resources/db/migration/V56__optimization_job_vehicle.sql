-- Xe của lần chạy tối ưu. Phương án (load_plans) dựng trên lòng thùng của xe lúc chạy; đổi xe của chuyến thì phương án cũ
-- thành lỗi thời nhưng trước đây không còn biết nó tính cho xe nào, nên Planner vẽ nó trong thùng của xe mới.
-- Chỉ THÊM cột (cho phép trống), không đổi cột cũ. Chạy lại an toàn (IF NOT EXISTS; điền dữ liệu chỉ ô đang trống).
-- Chạy tay trên DB dùng chung: chạy db/manual/2026-11-optimization-job-vehicle-preview.sql trước, sao lưu, rồi chạy file này,
-- rồi mới deploy LoadMasterService (ddl-auto: validate).

ALTER TABLE optimization_jobs
    ADD COLUMN IF NOT EXISTS vehicle_id BIGINT REFERENCES vehicles (id);

-- Job cũ: đổi xe đánh dấu lỗi thời MỌI phương án của chuyến, nên job còn phương án chưa lỗi thời chắc chắn tính cho xe
-- hiện tại của chuyến. Job chỉ có phương án lỗi thời (hoặc không có phương án) để trống: không biết chắc xe nào.
UPDATE optimization_jobs j
SET vehicle_id = t.vehicle_id
FROM trips t
WHERE t.id = j.trip_id
  AND j.vehicle_id IS NULL
  AND t.vehicle_id IS NOT NULL
  AND EXISTS (SELECT 1 FROM load_plans lp WHERE lp.job_id = j.id AND lp.is_stale IS NOT TRUE);
