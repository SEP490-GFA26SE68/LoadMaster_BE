-- Nhận hàng dọc đường: loại điểm của chuyến, yêu cầu nhận và kiện của yêu cầu.
-- Chỉ thêm cột và bảng mới (IF NOT EXISTS): không đổi ràng buộc CHECK nào có sẵn.

ALTER TABLE delivery_stops
    ADD COLUMN IF NOT EXISTS stop_type VARCHAR(20);

CREATE TABLE IF NOT EXISTS pickup_requests (
    id                  BIGSERIAL PRIMARY KEY,
    trip_id             BIGINT NOT NULL REFERENCES trips(id),
    company_id          BIGINT NOT NULL REFERENCES companies(id),
    pickup_name         VARCHAR(150) NOT NULL,
    pickup_address      VARCHAR(255) NOT NULL,
    pickup_latitude     DECIMAL(10, 7) NOT NULL,
    pickup_longitude    DECIMAL(10, 7) NOT NULL,
    delivery_name       VARCHAR(150) NOT NULL,
    delivery_address    VARCHAR(255) NOT NULL,
    delivery_latitude   DECIMAL(10, 7) NOT NULL,
    delivery_longitude  DECIMAL(10, 7) NOT NULL,
    deadline            TIMESTAMP,
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    validation_results  JSON,
    validated_at        TIMESTAMP,
    override_reason     VARCHAR(300),
    reject_reason       VARCHAR(300),
    created_by          BIGINT REFERENCES users(id),
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    approved_by         BIGINT REFERENCES users(id),
    approved_at         TIMESTAMP,
    rejected_by         BIGINT REFERENCES users(id),
    rejected_at         TIMESTAMP,
    pickup_stop_id      BIGINT REFERENCES delivery_stops(id),
    delivery_stop_id    BIGINT REFERENCES delivery_stops(id),
    layout              JSON,
    loaded_at           TIMESTAMP,
    delivered_at        TIMESTAMP,
    CONSTRAINT ck_pickup_requests_status
        CHECK (status IN ('PENDING', 'VALIDATED', 'REJECTED', 'APPROVED', 'LOADED', 'DELIVERED'))
);

CREATE INDEX IF NOT EXISTS idx_pickup_requests_trip_id ON pickup_requests(trip_id);

CREATE TABLE IF NOT EXISTS pickup_request_packages (
    id                  BIGSERIAL PRIMARY KEY,
    pickup_request_id   BIGINT NOT NULL REFERENCES pickup_requests(id) ON DELETE CASCADE,
    line_index          INT NOT NULL,
    package_code        VARCHAR(100) NOT NULL,
    length_cm           DECIMAL(10, 2) NOT NULL,
    width_cm            DECIMAL(10, 2) NOT NULL,
    height_cm           DECIMAL(10, 2) NOT NULL,
    weight_kg           DECIMAL(10, 2) NOT NULL,
    handling_class      VARCHAR(20) NOT NULL DEFAULT 'STANDARD',
    cargo_package_id    BIGINT UNIQUE REFERENCES packages(id),
    picked_at           TIMESTAMP,
    picked_by           BIGINT REFERENCES users(id),
    CONSTRAINT uk_pickup_request_package_line UNIQUE (pickup_request_id, line_index)
);

CREATE INDEX IF NOT EXISTS idx_pickup_request_packages_package ON pickup_request_packages(cargo_package_id);
