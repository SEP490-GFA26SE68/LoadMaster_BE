-- XEM TRƯỚC (chỉ đọc) cho db/migration/V55__rang_buoc_vat_ly_du_lieu.sql. Không ghi gì, chạy bao nhiêu lần cũng được.
--
-- V55 thêm cột số liệu vật lý (σ, ρ, tải sàn, lệch trọng tâm ngang, cảnh báo dữ liệu của job) và đổi tải chồng cũ sang
-- σ [kg/m²] theo spec 8.2. Chạy được cả TRƯỚC lẫn SAU V55 (chỉ đọc cột cũ).

-- 0. Cột mới đã có chưa (trước V55: không dòng nào).
SELECT table_name, column_name, data_type
FROM information_schema.columns
WHERE (table_name, column_name) IN (('package_types', 'top_load_capacity_kg_m2'), ('package_types', 'min_support_ratio'),
                                    ('cargo_package_packing_profiles', 'top_load_capacity_kg_m2'),
                                    ('vehicle_types', 'floor_load_capacity_kg_m2'),
                                    ('vehicle_types', 'max_cog_offset_ratio_y'),
                                    ('optimization_jobs', 'data_warnings'))
ORDER BY table_name, column_name;

-- 1. Loại kiện: σ sẽ được điền (dễ vỡ → 0; có tải chồng K → K / A_top). Loại kiện không có số nào → σ để trống.
SELECT pt.id, pt.company_id, pt.type_code, pt.name, pt.length, pt.width, pt.max_stack_weight_kg, pt.is_fragile,
       CASE
           WHEN pt.is_fragile IS TRUE THEN 0
           WHEN pt.max_stack_weight_kg IS NOT NULL AND pt.length > 0 AND pt.width > 0
               THEN round(pt.max_stack_weight_kg / (pt.length * pt.width / 10000.0), 2)
       END AS sigma_se_dien
FROM package_types pt
ORDER BY pt.company_id, pt.id;

-- 2. Hồ sơ kiện có số liệu tải chồng: σ sẽ được điền (stackable = false → 0; max_top_load_kg K → K / A_top).
SELECT pr.id AS profile_id, p.id AS package_id, p.package_type_id, pr.stackable, pr.max_top_load_kg, pr.fragility_level,
       coalesce(p.actual_length, pt.length) AS dai_cm, coalesce(p.actual_width, pt.width) AS rong_cm,
       CASE
           WHEN pr.stackable IS FALSE THEN 0
           WHEN pr.max_top_load_kg IS NOT NULL
               AND coalesce(p.actual_length, pt.length) > 0 AND coalesce(p.actual_width, pt.width) > 0
               THEN round(pr.max_top_load_kg / (coalesce(p.actual_length, pt.length)
                                                * coalesce(p.actual_width, pt.width) / 10000.0), 2)
       END AS sigma_se_dien
FROM cargo_package_packing_profiles pr
JOIN packages p ON p.id = pr.package_id
LEFT JOIN package_types pt ON pt.id = p.package_type_id
WHERE pr.stackable IS FALSE OR pr.max_top_load_kg IS NOT NULL OR pr.fragility_level IS NOT NULL
ORDER BY p.id;

-- 3. Đếm nhanh: bao nhiêu dòng được điền σ, bao nhiêu còn trống (sẽ hiện cảnh báo "thiếu σ" khi chạy tối ưu).
SELECT 'package_types' AS bang,
       count(*) FILTER (WHERE is_fragile IS TRUE
           OR (max_stack_weight_kg IS NOT NULL AND length > 0 AND width > 0)) AS se_dien,
       count(*) FILTER (WHERE NOT (is_fragile IS TRUE
           OR (max_stack_weight_kg IS NOT NULL AND length > 0 AND width > 0))) AS con_trong
FROM package_types
UNION ALL
SELECT 'packages khong co loai kien (bi cam xoay)', count(*), NULL
FROM packages
WHERE package_type_id IS NULL;
