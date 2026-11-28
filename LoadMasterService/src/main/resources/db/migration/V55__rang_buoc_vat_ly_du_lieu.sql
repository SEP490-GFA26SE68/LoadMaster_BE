-- Số liệu vật lý cho luật ràng buộc mới (spec "Ràng buộc vật lý và điểm phương án", mục 5, 8.1, 8.2; KE-HOACH-TOI-UU M3).
-- Chỉ THÊM cột, không xoá / không đổi nghĩa cột cũ. Chạy lại an toàn (IF NOT EXISTS; chuyển dữ liệu chỉ điền ô đang trống).
-- Chạy tay trên DB dùng chung: chạy db/manual/2026-11-rang-buoc-vat-ly-du-lieu-preview.sql trước, sao lưu, rồi chạy file này,
-- rồi mới deploy LoadMasterService (ddl-auto: validate) và OptimizeService.
--
--   σ  top_load_capacity_kg_m2  sức chịu nén mặt trên [kg/m²] — thay max_stack_weight_kg / is_fragile / max_top_load_kg (H7)
--   ρ  min_support_ratio        tỉ lệ đáy được đỡ tối thiểu, loại kiện (hồ sơ kiện đã có cột này) (H8)
--      floor_load_capacity_kg_m2 tải trọng sàn của loại xe [kg/m²] (H10)
--      max_cog_offset_ratio_y  lệch trọng tâm ngang tối đa / bề rộng (H11); trống = dùng max_cog_offset_ratio cũ
--      optimization_jobs.data_warnings  cảnh báo dữ liệu của lần chạy (kiện thiếu σ, kiện không có loại kiện …)

ALTER TABLE package_types
    ADD COLUMN IF NOT EXISTS top_load_capacity_kg_m2 NUMERIC(12, 2),
    ADD COLUMN IF NOT EXISTS min_support_ratio NUMERIC(5, 4);

ALTER TABLE cargo_package_packing_profiles
    ADD COLUMN IF NOT EXISTS top_load_capacity_kg_m2 NUMERIC(12, 2);

ALTER TABLE vehicle_types
    ADD COLUMN IF NOT EXISTS floor_load_capacity_kg_m2 NUMERIC(12, 2),
    ADD COLUMN IF NOT EXISTS max_cog_offset_ratio_y NUMERIC(5, 4);

ALTER TABLE optimization_jobs
    ADD COLUMN IF NOT EXISTS data_warnings jsonb NOT NULL DEFAULT '[]'::jsonb;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_package_types_top_load_capacity') THEN
        ALTER TABLE package_types ADD CONSTRAINT chk_package_types_top_load_capacity
            CHECK (top_load_capacity_kg_m2 IS NULL OR top_load_capacity_kg_m2 >= 0);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_package_types_support_ratio') THEN
        ALTER TABLE package_types ADD CONSTRAINT chk_package_types_support_ratio
            CHECK (min_support_ratio IS NULL OR min_support_ratio BETWEEN 0.5 AND 1);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_profile_top_load_capacity') THEN
        ALTER TABLE cargo_package_packing_profiles ADD CONSTRAINT chk_profile_top_load_capacity
            CHECK (top_load_capacity_kg_m2 IS NULL OR top_load_capacity_kg_m2 >= 0);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_vehicle_types_floor_load') THEN
        ALTER TABLE vehicle_types ADD CONSTRAINT chk_vehicle_types_floor_load
            CHECK (floor_load_capacity_kg_m2 IS NULL OR floor_load_capacity_kg_m2 > 0);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_vehicle_types_cog_offset_y') THEN
        ALTER TABLE vehicle_types ADD CONSTRAINT chk_vehicle_types_cog_offset_y
            CHECK (max_cog_offset_ratio_y IS NULL OR max_cog_offset_ratio_y BETWEEN 0 AND 0.5);
    END IF;
END $$;

-- Chuyển dữ liệu cũ sang σ (spec 8.2). Chỉ điền ô σ đang trống; cột cũ giữ nguyên.
-- Loại kiện: dễ vỡ → σ = 0; có tải chồng K (kg) → σ = K / A_top, A_top = dài × rộng / 10⁴ (m²).
UPDATE package_types
SET top_load_capacity_kg_m2 = 0
WHERE top_load_capacity_kg_m2 IS NULL
  AND is_fragile IS TRUE;

UPDATE package_types
SET top_load_capacity_kg_m2 = round(max_stack_weight_kg / (length * width / 10000.0), 2)
WHERE top_load_capacity_kg_m2 IS NULL
  AND max_stack_weight_kg IS NOT NULL
  AND length > 0
  AND width > 0;

-- Hồ sơ kiện: không cho chồng (stackable = false) → σ = 0; có max_top_load_kg K → σ = K / A_top, A_top theo kích thước
-- thật của kiện, thiếu thì theo loại kiện. fragility_level KHÔNG đổi: thang số chưa thống nhất (BE: số nguyên ≥ 0,
-- FE: NONE / LOW / MEDIUM / HIGH), nên không đoán "mức cao nhất" — kiện như vậy sẽ hiện trong cảnh báo thiếu σ.
UPDATE cargo_package_packing_profiles
SET top_load_capacity_kg_m2 = 0
WHERE top_load_capacity_kg_m2 IS NULL
  AND stackable IS FALSE;

UPDATE cargo_package_packing_profiles pr
SET top_load_capacity_kg_m2 = round(pr.max_top_load_kg
                                        / (coalesce(p.actual_length, pt.length) * coalesce(p.actual_width, pt.width)
                                           / 10000.0), 2)
FROM packages p
LEFT JOIN package_types pt ON pt.id = p.package_type_id
WHERE p.id = pr.package_id
  AND pr.top_load_capacity_kg_m2 IS NULL
  AND pr.max_top_load_kg IS NOT NULL
  AND coalesce(p.actual_length, pt.length) > 0
  AND coalesce(p.actual_width, pt.width) > 0;
