-- XEM TRƯỚC (chỉ đọc) cho db/migration/V56__optimization_job_vehicle.sql. Không ghi gì, chạy bao nhiêu lần cũng được.
-- Chạy được cả TRƯỚC lẫn SAU V56 (chỉ đọc cột cũ).

-- 0. Cột mới đã có chưa (trước V56: không dòng nào).
SELECT table_name, column_name, data_type
FROM information_schema.columns
WHERE table_name = 'optimization_jobs' AND column_name = 'vehicle_id';

-- 1. Bao nhiêu job sẽ được điền xe (còn phương án chưa lỗi thời), bao nhiêu để trống.
SELECT count(*) FILTER (WHERE t.vehicle_id IS NOT NULL
           AND EXISTS (SELECT 1 FROM load_plans lp WHERE lp.job_id = j.id AND lp.is_stale IS NOT TRUE)) AS se_dien,
       count(*) FILTER (WHERE NOT (t.vehicle_id IS NOT NULL
           AND EXISTS (SELECT 1 FROM load_plans lp WHERE lp.job_id = j.id AND lp.is_stale IS NOT TRUE))) AS de_trong
FROM optimization_jobs j
JOIN trips t ON t.id = j.trip_id;
