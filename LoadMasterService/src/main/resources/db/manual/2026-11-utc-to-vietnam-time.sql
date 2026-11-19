-- SỬA TAY MỘT LẦN — KHÔNG phải migration (Flyway chỉ quét db/migration). Chạy bằng tay, sau khi đã chạy
-- 2026-11-utc-to-vietnam-time-preview.sql và đọc kết quả. Sao lưu DB trước.
--
-- Việc: cộng 7 giờ vào created_at của các dòng OptimizeService ghi bằng datetime.utcnow trước bản sửa giờ (B17),
-- để cả bảng cùng là giờ Việt Nam như LoadMasterService ghi và đọc. Chỉ hai nhóm dòng (xem file preview):
--   * optimization_jobs: parent_plan_id IS NULL (job Java tạo luôn có parent_plan_id);
--   * audit_logs: PLAN_APPROVED có old_values rỗng và new_values có khoá approved_by (bản Python).
-- và chỉ dòng có created_at < deployed_vn - 7 giờ (xem giải thích deployed_vn ở file preview).
--
-- Xét kiểu cột của từng bảng:
--   * timestamp without time zone: giờ UTC ghi vào thành giờ đồng hồ chậm 7 giờ -> sửa;
--   * timestamp with time zone: Python ghi giờ UTC trong phiên UTC nên thời điểm lưu đã đúng (đọc ra +07 khớp
--     dòng Java ghi ngay sau) -> KHÔNG sửa, chỉ báo NOTICE. Trên DB dùng chung, audit_logs là kiểu này;
--   * kiểu khác hoặc không có cột: dừng, không sửa gì.
--
-- An toàn khi chạy lại: bảng manual_fix_runs đánh dấu lần chạy; đã có dấu thì không làm gì. Hai người chạy cùng
-- lúc thì người sau chờ người trước rồi thấy dấu. Mỗi dòng đã sửa được lưu ở manual_fix_utc_rows (giá trị cũ và
-- mới) để kiểm lại hoặc trả về.
--
-- Trả về (nếu cần):
--   UPDATE optimization_jobs t SET created_at = f.old_created_at FROM manual_fix_utc_rows f
--    WHERE f.table_name = 'optimization_jobs' AND f.row_id = t.id AND t.created_at = f.new_created_at;
--   UPDATE audit_logs t SET created_at = f.old_created_at FROM manual_fix_utc_rows f
--    WHERE f.table_name = 'audit_logs' AND f.row_id = t.id AND t.created_at = f.new_created_at;
--   DELETE FROM manual_fix_runs WHERE name = '2026-11-utc-to-vietnam-time';

BEGIN;

CREATE TABLE IF NOT EXISTS manual_fix_runs (
    name       VARCHAR(100) PRIMARY KEY,
    applied_at TIMESTAMP    NOT NULL,
    note       TEXT
);

CREATE TABLE IF NOT EXISTS manual_fix_utc_rows (
    table_name     VARCHAR(50) NOT NULL,
    row_id         BIGINT      NOT NULL,
    old_created_at TIMESTAMP   NOT NULL,
    new_created_at TIMESTAMP   NOT NULL,
    PRIMARY KEY (table_name, row_id)
);

DO $$
DECLARE
    -- ĐIỀN: giờ Việt Nam lúc chắc chắn không còn OptimizeService bản cũ ghi vào DB này (cùng giá trị với preview).
    -- Ví dụ: deployed_vn TIMESTAMP := TIMESTAMP '2026-11-12 23:59:59';
    deployed_vn TIMESTAMP := NULL;
    fix_name    CONSTANT TEXT := '2026-11-utc-to-vietnam-time';
    naive       CONSTANT TEXT := 'timestamp without time zone';
    aware       CONSTANT TEXT := 'timestamp with time zone';
    jobs_type   TEXT;
    logs_type   TEXT;
    claimed     INTEGER;
    jobs_fixed  INTEGER := 0;
    logs_fixed  INTEGER := 0;
BEGIN
    IF deployed_vn IS NULL THEN
        RAISE EXCEPTION 'Chưa điền deployed_vn: không sửa gì';
    END IF;

    SELECT data_type INTO jobs_type FROM information_schema.columns
    WHERE table_schema = current_schema() AND table_name = 'optimization_jobs' AND column_name = 'created_at';
    SELECT data_type INTO logs_type FROM information_schema.columns
    WHERE table_schema = current_schema() AND table_name = 'audit_logs' AND column_name = 'created_at';

    IF jobs_type IS NULL OR jobs_type NOT IN (naive, aware) THEN
        RAISE EXCEPTION 'optimization_jobs.created_at có kiểu %: không sửa gì', coalesce(jobs_type, '(không có cột)');
    END IF;
    IF logs_type IS NULL OR logs_type NOT IN (naive, aware) THEN
        RAISE EXCEPTION 'audit_logs.created_at có kiểu %: không sửa gì', coalesce(logs_type, '(không có cột)');
    END IF;

    -- Đánh dấu trước: lần chạy thứ hai (kể cả đang chạy song song) không chèn được dấu thì dừng.
    INSERT INTO manual_fix_runs (name, applied_at, note)
    VALUES (fix_name, now()::timestamp,
            'deployed_vn = ' || deployed_vn || '; optimization_jobs: ' || jobs_type || '; audit_logs: ' || logs_type)
    ON CONFLICT (name) DO NOTHING;
    GET DIAGNOSTICS claimed = ROW_COUNT;
    IF claimed = 0 THEN
        RAISE NOTICE 'Đã chạy % trước đó: không làm gì', fix_name;
        RETURN;
    END IF;

    IF jobs_type = naive THEN
        INSERT INTO manual_fix_utc_rows (table_name, row_id, old_created_at, new_created_at)
        SELECT 'optimization_jobs', j.id, j.created_at, j.created_at + INTERVAL '7 hours'
        FROM optimization_jobs j
        WHERE j.parent_plan_id IS NULL
          AND j.created_at IS NOT NULL
          AND j.created_at < deployed_vn - INTERVAL '7 hours';

        UPDATE optimization_jobs t SET created_at = f.new_created_at
        FROM manual_fix_utc_rows f
        WHERE f.table_name = 'optimization_jobs' AND f.row_id = t.id AND t.created_at = f.old_created_at;
        GET DIAGNOSTICS jobs_fixed = ROW_COUNT;
    ELSE
        RAISE NOTICE 'Bỏ qua optimization_jobs: created_at là timestamp with time zone, Python ghi giờ UTC trong phiên UTC nên thời điểm đã đúng';
    END IF;

    IF logs_type = naive THEN
        INSERT INTO manual_fix_utc_rows (table_name, row_id, old_created_at, new_created_at)
        SELECT 'audit_logs', a.id, a.created_at, a.created_at + INTERVAL '7 hours'
        FROM audit_logs a
        WHERE a.action_type = 'PLAN_APPROVED'
          AND a.old_values IS NULL
          AND a.new_values IS NOT NULL
          AND a.new_values::jsonb ? 'approved_by'
          AND a.created_at IS NOT NULL
          AND a.created_at < deployed_vn - INTERVAL '7 hours';

        UPDATE audit_logs t SET created_at = f.new_created_at
        FROM manual_fix_utc_rows f
        WHERE f.table_name = 'audit_logs' AND f.row_id = t.id AND t.created_at = f.old_created_at;
        GET DIAGNOSTICS logs_fixed = ROW_COUNT;
    ELSE
        RAISE NOTICE 'Bỏ qua audit_logs: created_at là timestamp with time zone, Python ghi giờ UTC trong phiên UTC nên thời điểm đã đúng';
    END IF;

    RAISE NOTICE 'Đã cộng 7 giờ: % job tối ưu, % dòng nhật ký PLAN_APPROVED', jobs_fixed, logs_fixed;
END $$;

COMMIT;
