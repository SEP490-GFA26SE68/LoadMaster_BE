-- Chuông thông báo: thông báo là dòng nhật ký (audit_logs) mà người dùng nhận theo vai trò.
-- Bảng này chỉ ghi người nào đã đọc dòng nào.
CREATE TABLE IF NOT EXISTS public.notification_reads (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT      NOT NULL,
    audit_log_id BIGINT      NOT NULL,
    read_at      TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_notification_reads_user_log UNIQUE (user_id, audit_log_id)
);

CREATE INDEX IF NOT EXISTS idx_notification_reads_user ON public.notification_reads (user_id);
