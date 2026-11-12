-- Supabase không chạy Flyway: schema ở đó đi theo spring.jpa.hibernate.ddl-auto=update, mà ddl-auto không bao giờ sửa
-- CHECK constraint đã có. Hibernate sinh CHECK cho mỗi cột @Enumerated(STRING) lúc tạo bảng, Postgres đặt tên
-- <bảng>_<cột>_check, nên giá trị enum thêm sau đó bị chính DB từ chối khi ghi. File này chạy lại bao nhiêu lần cũng được.
--
-- Giá trị lấy đúng từ enum Java:
--   users.status               UserStatus            (thêm FLAGGED)
--   support_tickets.category   TicketCategory        (thêm TRIAL_ACCOUNT_UNLOCK)
--   optimization_jobs.objective OptimizationObjective (thêm MIN_REHANDLING)

ALTER TABLE IF EXISTS users
    DROP CONSTRAINT IF EXISTS users_status_check,
    ADD CONSTRAINT users_status_check
        CHECK (status IN ('ACTIVE', 'INACTIVE', 'LOCKED', 'FLAGGED'));

ALTER TABLE IF EXISTS support_tickets
    DROP CONSTRAINT IF EXISTS support_tickets_category_check,
    ADD CONSTRAINT support_tickets_category_check
        CHECK (category IN ('BILLING', 'ALGORITHM_ERROR', 'SYSTEM_BUG', 'TRIAL_ACCOUNT_UNLOCK'));

ALTER TABLE IF EXISTS optimization_jobs
    DROP CONSTRAINT IF EXISTS optimization_jobs_objective_check,
    ADD CONSTRAINT optimization_jobs_objective_check
        CHECK (objective IN ('MAX_VOLUME', 'AXLE_BALANCE', 'MIN_REHANDLING'));

-- Mốc thỏa thuận dùng thử thành EXPIRED (TrialAgreementEnforcementJob tính 30 ngày xóa cứng từ đây). ddl-auto=update
-- cũng tự thêm cột này; dòng dưới để môi trường chạy Flyway có cùng schema. Bản ghi EXPIRED cũ để NULL: job điền giờ
-- chạy đầu tiên của nó.
ALTER TABLE IF EXISTS company_agreements
    ADD COLUMN IF NOT EXISTS expired_at TIMESTAMP(6);
