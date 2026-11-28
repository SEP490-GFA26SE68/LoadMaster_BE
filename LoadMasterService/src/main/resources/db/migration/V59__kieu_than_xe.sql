-- Kiểu thân xe của loại xe (Planner 3D đợt 6). Chỉ đổi hình xe trong khung 3D của web (Planner, kho, tài xế): không vào yêu
-- cầu tối ưu, đổi giá trị không làm phương án lỗi thời. Trống = web tự chọn kiểu theo kích thước lòng thùng.
-- Chỉ THÊM cột (cho phép trống) và một ràng buộc kiểm giá trị, không đổi cột cũ, không sửa dữ liệu: dòng cũ để trống.
-- Chạy lại an toàn (IF NOT EXISTS, ràng buộc chỉ thêm khi chưa có).
-- Chạy tay trên DB dùng chung: sao lưu, chạy db/manual/2026-11-kieu-than-xe-preview.sql, chạy file này, rồi mới deploy
-- LoadMasterService (ddl-auto: validate đòi cột này).
--
--   vehicle_types.body_style   VAN_MINI_FLAT | VAN_MINI_HOOD | VAN_MID | VAN_LARGE
--                              | TRUCK_BOX | TRUCK_CANVAS | TRUCK_FLATBED | TRUCK_REEFER, hoặc trống

ALTER TABLE vehicle_types
    ADD COLUMN IF NOT EXISTS body_style VARCHAR(32);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_vehicle_types_body_style') THEN
        ALTER TABLE vehicle_types ADD CONSTRAINT chk_vehicle_types_body_style
            CHECK (body_style IS NULL OR body_style IN ('VAN_MINI_FLAT', 'VAN_MINI_HOOD', 'VAN_MID', 'VAN_LARGE',
                                                        'TRUCK_BOX', 'TRUCK_CANVAS', 'TRUCK_FLATBED', 'TRUCK_REEFER'));
    END IF;
END $$;
