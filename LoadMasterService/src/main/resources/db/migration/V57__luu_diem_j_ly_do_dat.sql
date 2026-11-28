-- Điểm J của phương án và lý do đặt từng kiện (M6, cờ PLAN_SCORE_J của OptimizeService). Trước đây dịch vụ tối ưu tính các
-- số này nhưng không lưu, nên web không đọc được. Dịch vụ tối ưu ghi; LoadMasterService chỉ đọc. Mọi cột để trống khi cờ tắt,
-- ở phương án cũ và ở bản duyệt dựng từ chỉnh tay (chỉnh tay làm các số này sai).
-- Chỉ THÊM cột (cho phép trống), không đổi cột cũ, không sửa dữ liệu. Chạy lại an toàn (IF NOT EXISTS).
-- Chạy tay trên DB dùng chung: sao lưu, chạy file này, rồi mới deploy LoadMasterService (ddl-auto: validate) và OptimizeService
-- (entity SQLAlchemy đọc cả các cột này).
--
--   load_plans.plan_score          điểm J + phân rã theo hồ sơ (JSON: profile, profile_name, assumed_weights, j, u_vol, u_n,
--                                  w_vol, w_n, components[code, name, value, weight, contribution, computed])
--   load_plans.candidate_profile   hồ sơ đã chọn phương án ứng viên này (A | B | C)
--   load_plans.duplicate_of        nhãn phương án trước đã chọn trùng cách xếp (vd "A")
--   package_placements.reason_codes             mã lý do đặt (JSON array: ZONE_STOP_2, HEAVY_LOW, PINNED, …)
--   package_placements.stack_pressure_kg_m2     áp lực trên mặt trên của kiện [kg/m²] (H7); 0 khi không có gì đè
--   package_placements.top_load_capacity_kg_m2  σ dùng khi kiểm H7 [kg/m²]; trống = kiện không nhận tải
--   package_placements.support_ratio            diện tích đáy được đỡ / diện tích đáy (H8); 1 trên sàn
--   package_placements.distance_to_door_cm      khoảng cách từ mặt sau kiện tới cửa [cm]

ALTER TABLE load_plans
    ADD COLUMN IF NOT EXISTS plan_score jsonb,
    ADD COLUMN IF NOT EXISTS candidate_profile VARCHAR(20),
    ADD COLUMN IF NOT EXISTS duplicate_of VARCHAR(1);

ALTER TABLE package_placements
    ADD COLUMN IF NOT EXISTS reason_codes jsonb,
    ADD COLUMN IF NOT EXISTS stack_pressure_kg_m2 NUMERIC(16, 3),
    ADD COLUMN IF NOT EXISTS top_load_capacity_kg_m2 NUMERIC(16, 3),
    ADD COLUMN IF NOT EXISTS support_ratio NUMERIC(5, 4),
    ADD COLUMN IF NOT EXISTS distance_to_door_cm NUMERIC(10, 3);
