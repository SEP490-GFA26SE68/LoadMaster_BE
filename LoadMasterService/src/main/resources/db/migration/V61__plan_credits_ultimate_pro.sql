-- Gói Ultimate thôi "không giới hạn": cấp 30.000 credit/tháng và trừ credit như mọi gói (lần đầu 2, chạy lại 1).
-- Gói Pro cấp 10.000 credit/tháng (trước đây code cấp 15.000 trong khi mô tả ghi 10.000).
-- SubscriptionPlanInitializer chỉ tạo gói khi DB chưa có, nên các gói đã có trên Supabase phải cập nhật bằng câu lệnh này.
-- Chạy lại nhiều lần vẫn cho cùng kết quả. Không đụng số dư credit của công ty nào.

UPDATE subscription_plans
SET monthly_credits = 30000,
    features = '["Toàn bộ 3 thuật toán + AI Heuristic (EP-DBLF + GA + AI)","30.000 Credits / tháng (2 Credits/lần đầu, 1 Credit/lần chạy lại)","Ghim kiện hàng & Rerun tinh chỉnh chỉ 1 Credit","Cam kết SLA 99.5% & Chuyên gia hỗ trợ riêng"]'
WHERE plan_code = 'ULTIMATE_MONTHLY';

UPDATE subscription_plans
SET monthly_credits = 10000,
    features = '["Tối ưu 3D nâng cao (EP-DBLF + Genetic Algorithm)","10.000 Credits / tháng (2 Credits/lần đầu, 1 Credit/lần chạy lại)","Tối ưu lộ trình đa điểm dừng (LIFO)","Hỗ trợ kỹ thuật ưu tiên 24/7"]'
WHERE plan_code = 'PRO_MONTHLY';
