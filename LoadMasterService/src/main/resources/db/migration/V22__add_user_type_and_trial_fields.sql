-- V22: Add user_type to users, trial quota fields, and update status check constraint for trial_account_requests

-- 1. Add user_type to users with default 'OFFICIAL'
ALTER TABLE users ADD COLUMN IF NOT EXISTS user_type VARCHAR(20) DEFAULT 'OFFICIAL';
UPDATE users SET user_type = 'OFFICIAL' WHERE user_type IS NULL;

-- 2. Add manager review notes and trial quotas to trial_account_requests
ALTER TABLE trial_account_requests ADD COLUMN IF NOT EXISTS manager_note TEXT;
ALTER TABLE trial_account_requests ADD COLUMN IF NOT EXISTS trial_days INTEGER;
ALTER TABLE trial_account_requests ADD COLUMN IF NOT EXISTS trial_credits INTEGER;

-- 3. Update status check constraint to include PENDING_ADMIN_CREATION
ALTER TABLE trial_account_requests DROP CONSTRAINT IF EXISTS trial_account_requests_status_check;
ALTER TABLE trial_account_requests ADD CONSTRAINT trial_account_requests_status_check
    CHECK (status IN ('PENDING', 'PENDING_ADMIN_CREATION', 'APPROVED', 'REJECTED'));
