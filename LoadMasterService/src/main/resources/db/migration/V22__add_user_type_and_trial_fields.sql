-- V22: Add user_type to users and trial quota fields to trial_account_requests

-- 1. Add user_type to users with default 'OFFICIAL'
ALTER TABLE users ADD COLUMN IF NOT EXISTS user_type VARCHAR(20) DEFAULT 'OFFICIAL';
UPDATE users SET user_type = 'OFFICIAL' WHERE user_type IS NULL;

-- 2. Add manager review notes and trial quotas to trial_account_requests
ALTER TABLE trial_account_requests ADD COLUMN IF NOT EXISTS manager_note TEXT;
ALTER TABLE trial_account_requests ADD COLUMN IF NOT EXISTS trial_days INTEGER;
ALTER TABLE trial_account_requests ADD COLUMN IF NOT EXISTS trial_credits INTEGER;
