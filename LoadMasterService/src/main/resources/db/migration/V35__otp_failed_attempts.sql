-- Đếm số lần nhập sai OTP của phiên đăng ký (cột thêm mới, cho phép NULL; dữ liệu cũ không đổi)
ALTER TABLE guest_registration_sessions ADD COLUMN IF NOT EXISTS otp_failed_attempts INTEGER;
ALTER TABLE trial_registration_sessions ADD COLUMN IF NOT EXISTS otp_failed_attempts INTEGER;
