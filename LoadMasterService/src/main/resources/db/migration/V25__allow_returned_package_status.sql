-- Kiện hoàn trả: kiện có sự cố lúc giao mà chưa dỡ khi tài xế hoàn tất điểm.
ALTER TABLE packages DROP CONSTRAINT IF EXISTS packages_status_check;
ALTER TABLE packages ADD CONSTRAINT packages_status_check
    CHECK (status IN ('PENDING', 'PLANNED', 'LOADED', 'DELIVERED', 'RETURNED', 'CANCELLED'));
