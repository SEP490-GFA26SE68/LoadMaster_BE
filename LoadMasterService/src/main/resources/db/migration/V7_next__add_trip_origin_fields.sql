
-- Bổ sung tên chuyến và thông tin kho xuất phát.
-- Giữ nguyên các cột và dữ liệu cũ của bảng trips.

ALTER TABLE trips
    ADD COLUMN trip_name VARCHAR(150),
    ADD COLUMN origin_name VARCHAR(150),
    ADD COLUMN origin_address VARCHAR(255),
    ADD COLUMN origin_latitude NUMERIC(10, 7),
    ADD COLUMN origin_longitude NUMERIC(10, 7);
