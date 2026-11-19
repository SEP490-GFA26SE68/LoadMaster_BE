-- XEM TRƯỚC (chỉ đọc) cho 2026-11-utc-to-vietnam-time.sql. Không ghi gì, chạy bao nhiêu lần cũng được.
--
-- Trước bản sửa giờ (B17, commit "fix(time): hai dịch vụ cùng ghi giờ Việt Nam...", 12/11/2026), OptimizeService
-- ghi created_at bằng datetime.utcnow (giờ UTC không múi giờ) trong phiên UTC, còn LoadMasterService ghi giờ Việt Nam.
-- Chỉ hai chỗ Python từng ghi giờ:
--   * optimization_jobs.created_at — job do Python tạo (Java chỉ tạo job chạy lại, luôn có parent_plan_id);
--   * audit_logs.created_at — PLAN_APPROVED do Python ghi (old_values rỗng, new_values có khoá approved_by;
--     bản Java của PLAN_APPROVED có old_values và sourcePlanId).
-- Dòng chỉ sai khi cột là timestamp without time zone (giờ đồng hồ chậm 7 giờ). Cột timestamp with time zone nhận
-- giờ UTC trong phiên UTC thành đúng thời điểm, nên không sửa: file sửa bỏ qua bảng đó (trên DB dùng chung là
-- audit_logs) và các câu dưới đây không liệt kê dòng nào của nó.
--
-- deployed_vn: giờ Việt Nam lúc chắc chắn KHÔNG còn OptimizeService bản cũ (trước B17) nào ghi vào DB này.
-- Đặt muộn hơn thực tế tới 7 giờ vẫn đúng (dòng giờ UTC cũ luôn nhỏ hơn deployed_vn - 7 giờ, dòng giờ Việt Nam
-- mới luôn lớn hơn hoặc bằng lúc triển khai); đặt sớm hơn thực tế thì sót dòng. Dùng CÙNG giá trị ở file sửa.

-- 0. Kiểu cột và việc file sửa sẽ làm với từng bảng.
SELECT table_name, column_name, data_type,
       CASE data_type
           WHEN 'timestamp without time zone' THEN 'sửa (+7 giờ)'
           WHEN 'timestamp with time zone' THEN 'bỏ qua: thời điểm đã đúng'
           ELSE 'kiểu lạ: file sửa sẽ dừng'
       END AS action
FROM information_schema.columns
WHERE table_schema = current_schema()
  AND table_name IN ('optimization_jobs', 'audit_logs')
  AND column_name = 'created_at';

-- 1. Đã chạy file sửa chưa (bảng đánh dấu chưa có thì câu này báo lỗi "does not exist": nghĩa là chưa chạy).
-- SELECT * FROM manual_fix_runs WHERE name = '2026-11-utc-to-vietnam-time';

-- 2. Số dòng sẽ sửa, theo bảng (chỉ bảng có cột timestamp without time zone). ĐIỀN deployed_vn.
WITH params AS (SELECT TIMESTAMP '2026-11-12 23:59:59' AS deployed_vn),
naive AS (
    SELECT table_name FROM information_schema.columns
    WHERE table_schema = current_schema() AND column_name = 'created_at'
      AND table_name IN ('optimization_jobs', 'audit_logs')
      AND data_type = 'timestamp without time zone'
),
candidates AS (
    SELECT 'optimization_jobs' AS table_name, j.id, j.created_at::timestamp AS created_at
    FROM optimization_jobs j, params p
    WHERE EXISTS (SELECT 1 FROM naive WHERE table_name = 'optimization_jobs')
      AND j.parent_plan_id IS NULL
      AND j.created_at IS NOT NULL
      AND j.created_at::timestamp < p.deployed_vn - INTERVAL '7 hours'
    UNION ALL
    SELECT 'audit_logs', a.id, a.created_at::timestamp
    FROM audit_logs a, params p
    WHERE EXISTS (SELECT 1 FROM naive WHERE table_name = 'audit_logs')
      AND a.action_type = 'PLAN_APPROVED'
      AND a.old_values IS NULL
      AND a.new_values IS NOT NULL
      AND a.new_values::jsonb ? 'approved_by'
      AND a.created_at IS NOT NULL
      AND a.created_at::timestamp < p.deployed_vn - INTERVAL '7 hours'
)
SELECT table_name, count(*) AS rows_to_fix, min(created_at) AS oldest, max(created_at) AS newest
FROM candidates
GROUP BY table_name
ORDER BY table_name;

-- 3. 50 dòng gần mốc nhất: giá trị hiện tại và giá trị sau khi sửa (+7 giờ). ĐIỀN deployed_vn.
WITH params AS (SELECT TIMESTAMP '2026-11-12 23:59:59' AS deployed_vn),
naive AS (
    SELECT table_name FROM information_schema.columns
    WHERE table_schema = current_schema() AND column_name = 'created_at'
      AND table_name IN ('optimization_jobs', 'audit_logs')
      AND data_type = 'timestamp without time zone'
)
SELECT 'optimization_jobs' AS table_name, j.id, j.job_uuid::text AS ref, j.created_at::timestamp AS now_value,
       j.created_at::timestamp + INTERVAL '7 hours' AS fixed_value
FROM optimization_jobs j, params p
WHERE EXISTS (SELECT 1 FROM naive WHERE table_name = 'optimization_jobs')
  AND j.parent_plan_id IS NULL
  AND j.created_at IS NOT NULL
  AND j.created_at::timestamp < p.deployed_vn - INTERVAL '7 hours'
UNION ALL
SELECT 'audit_logs', a.id, a.entity_id::text, a.created_at::timestamp, a.created_at::timestamp + INTERVAL '7 hours'
FROM audit_logs a, params p
WHERE EXISTS (SELECT 1 FROM naive WHERE table_name = 'audit_logs')
  AND a.action_type = 'PLAN_APPROVED'
  AND a.old_values IS NULL
  AND a.new_values IS NOT NULL
  AND a.new_values::jsonb ? 'approved_by'
  AND a.created_at IS NOT NULL
  AND a.created_at::timestamp < p.deployed_vn - INTERVAL '7 hours'
ORDER BY now_value DESC
LIMIT 50;

-- 4. Dòng của Python nằm giữa (deployed_vn - 7 giờ) và deployed_vn ở bảng sẽ sửa: KHÔNG được sửa. Nếu có dòng ở
-- đây mà biết là giờ UTC cũ, deployed_vn đang đặt quá sớm.
WITH params AS (SELECT TIMESTAMP '2026-11-12 23:59:59' AS deployed_vn),
naive AS (
    SELECT table_name FROM information_schema.columns
    WHERE table_schema = current_schema() AND column_name = 'created_at'
      AND table_name IN ('optimization_jobs', 'audit_logs')
      AND data_type = 'timestamp without time zone'
)
SELECT 'optimization_jobs' AS table_name, j.id, j.created_at::timestamp AS created_at
FROM optimization_jobs j, params p
WHERE EXISTS (SELECT 1 FROM naive WHERE table_name = 'optimization_jobs')
  AND j.parent_plan_id IS NULL
  AND j.created_at::timestamp >= p.deployed_vn - INTERVAL '7 hours' AND j.created_at::timestamp < p.deployed_vn
UNION ALL
SELECT 'audit_logs', a.id, a.created_at::timestamp
FROM audit_logs a, params p
WHERE EXISTS (SELECT 1 FROM naive WHERE table_name = 'audit_logs')
  AND a.action_type = 'PLAN_APPROVED' AND a.old_values IS NULL
  AND a.new_values IS NOT NULL AND a.new_values::jsonb ? 'approved_by'
  AND a.created_at::timestamp >= p.deployed_vn - INTERVAL '7 hours' AND a.created_at::timestamp < p.deployed_vn
ORDER BY created_at;
