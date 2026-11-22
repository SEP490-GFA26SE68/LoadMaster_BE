-- Mã ràng buộc cứng của dịch vụ tối ưu (AXLE_CONFIG_MISSING, PACKAGE_CANNOT_PASS_DOOR, …).
-- Trước đây chỉ lưu rejection_reason = CONSTRAINT_VIOLATED nên UI không nói được ràng buộc nào.
ALTER TABLE unplaced_packages
    ADD COLUMN IF NOT EXISTS violated_constraints jsonb NOT NULL DEFAULT '[]'::jsonb;
