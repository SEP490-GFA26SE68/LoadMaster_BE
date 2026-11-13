-- Báo cáo chuyến và huỷ chuyến đang vận chuyển. Mọi cột đều cho phép null: dòng có từ trước để trống.

-- Giờ tài xế xuất phát và số kiện thành hoàn trả khi chuyến bị huỷ lúc đang vận chuyển.
ALTER TABLE trips ADD COLUMN IF NOT EXISTS departed_at TIMESTAMP;
ALTER TABLE trips ADD COLUMN IF NOT EXISTS cancel_returned_count INTEGER;

-- Giờ tài xế hoàn tất từng điểm.
ALTER TABLE delivery_stops ADD COLUMN IF NOT EXISTS completed_at TIMESTAMP;

-- Cách đối chiếu kiện bằng nhãn: QR (quét) hoặc CODE (gõ mã); để trống là QR.
ALTER TABLE placement_confirmations ADD COLUMN IF NOT EXISTS method VARCHAR(10);
ALTER TABLE unload_confirmations ADD COLUMN IF NOT EXISTS method VARCHAR(10);
ALTER TABLE pickup_request_packages ADD COLUMN IF NOT EXISTS pick_method VARCHAR(10);
