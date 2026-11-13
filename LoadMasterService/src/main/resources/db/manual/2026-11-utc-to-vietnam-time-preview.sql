-- XEM TRƯỚC (chỉ đọc) cho 2026-11-utc-to-vietnam-time.sql. Không ghi gì, chạy bao nhiêu lần cũng được.
--
-- Trước bản sửa giờ (B17, commit "fix(time): hai dịch vụ cùng ghi giờ Việt Nam...", 12/11/2026), OptimizeService
-- ghi created_at bằng datetime.utcnow vào cột timestamp không múi giờ, còn LoadMasterService ghi giờ Việt Nam.
-- Các dòng đó chậm 7 giờ. Chỉ hai chỗ Python từng ghi giờ:
--   * optimization_jobs.created_at — job do Python tạo (Java chỉ tạo job chạy lại, luôn có parent_plan_id);
--   * audit_logs.created_at — PLAN_APPROVED do Python ghi (old_values rỗng, new_values có khoá approved_by;
--     bản Java của PLAN_APPROVED có old_values và sourcePlanId).
--
-- deployed_vn: giờ Việt Nam lúc chắc chắn KHÔNG còn OptimizeService bản cũ (trước B17) nào ghi vào DB này.
-- Đặt muộn hơn thực tế tới 7 giờ vẫn đúng (dòng giờ UTC cũ luôn nhỏ hơn deployed_vn - 7 giờ, dòng giờ Việt Nam
-- mới luôn lớn hơn hoặc bằng lúc triển khai); đặt sớm hơn thực tế thì sót dòng. Dùng CÙNG giá trị ở file sửa.

-- 0. Kiểu cột: chỉ sửa khi là timestamp without time zone. Cột timestamptz thì KHÔNG chạy file sửa.
SELECT table_name, column_name, data_type
FROM information_schema.columns
WHERE table_schema = current_schema()
  AND table_name IN ('optimization_jobs', 'audit_logs')
  AND column_name = 'created_at';

-- 1. Đã chạy file sửa chưa (bảng đánh dấu chưa có thì câu này báo lỗi "does not exist": nghĩa là chưa chạy).
-- SELECT * FROM manual_fix_runs WHERE name = '2026-11-utc-to-vietnam-time';

-- 2. Số dòng sẽ sửa, theo bảng. ĐIỀN deployed_vn.
WITH params AS (SELECT TIMESTAMP '2026-11-12 23:59:59' AS deployed_vn),
candidates AS (
    SELECT 'optimization_jobs' AS table_name, j.id, j.created_at
    FROM optimization_jobs j, params p
    WHERE j.parent_plan_id IS NULL
      AND j.created_at IS NOT NULL
      AND j.created_at < p.deployed_vn - INTERVAL '7 hours'
    UNION ALL
    SELECT 'audit_logs', a.id, a.created_at
    FROM audit_logs a, params p
    WHERE a.action_type = 'PLAN_APPROVED'
      AND a.old_values IS NULL
      AND a.new_values IS NOT NULL
      AND a.new_values::jsonb ? 'approved_by'
      AND a.created_at IS NOT NULL
      AND a.created_at < p.deployed_vn - INTERVAL '7 hours'
)
SELECT table_name, count(*) AS rows_to_fix, min(created_at) AS oldest, max(created_at) AS newest
FROM candidates
GROUP BY table_name
ORDER BY table_name;

-- 3. 50 dòng gần mốc nhất: giá trị hiện tại và giá trị sau khi sửa (+7 giờ). ĐIỀN deployed_vn.
WITH params AS (SELECT TIMESTAMP '2026-11-12 23:59:59' AS deployed_vn)
SELECT 'optimization_jobs' AS table_name, j.id, j.job_uuid AS ref, j.created_at AS now_value,
       j.created_at + INTERVAL '7 hours' AS fixed_value
FROM optimization_jobs j, params p
WHERE j.parent_plan_id IS NULL
  AND j.created_at IS NOT NULL
  AND j.created_at < p.deployed_vn - INTERVAL '7 hours'
UNION ALL
SELECT 'audit_logs', a.id, a.entity_id, a.created_at, a.created_at + INTERVAL '7 hours'
FROM audit_logs a, params p
WHERE a.action_type = 'PLAN_APPROVED'
  AND a.old_values IS NULL
  AND a.new_values IS NOT NULL
  AND a.new_values::jsonb ? 'approved_by'
  AND a.created_at IS NOT NULL
  AND a.created_at < p.deployed_vn - INTERVAL '7 hours'
ORDER BY now_value DESC
LIMIT 50;

-- 4. Dòng của Python nằm giữa (deployed_vn - 7 giờ) và deployed_vn: KHÔNG được sửa. Nếu có dòng ở đây mà biết là
-- giờ UTC cũ, deployed_vn đang đặt quá sớm.
WITH params AS (SELECT TIMESTAMP '2026-11-12 23:59:59' AS deployed_vn)
SELECT 'optimization_jobs' AS table_name, j.id, j.created_at
FROM optimization_jobs j, params p
WHERE j.parent_plan_id IS NULL
  AND j.created_at >= p.deployed_vn - INTERVAL '7 hours' AND j.created_at < p.deployed_vn
UNION ALL
SELECT 'audit_logs', a.id, a.created_at
FROM audit_logs a, params p
WHERE a.action_type = 'PLAN_APPROVED' AND a.old_values IS NULL
  AND a.new_values IS NOT NULL AND a.new_values::jsonb ? 'approved_by'
  AND a.created_at >= p.deployed_vn - INTERVAL '7 hours' AND a.created_at < p.deployed_vn
ORDER BY created_at;
