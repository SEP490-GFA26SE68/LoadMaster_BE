-- V47: Bảng phiếu xin nhận chuyến của tài xế
CREATE TABLE IF NOT EXISTS trip_claims (
    id BIGSERIAL PRIMARY KEY,
    trip_id BIGINT NOT NULL,
    driver_user_id BIGINT NOT NULL,
    company_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    note VARCHAR(300),
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    decided_by BIGINT,
    decided_at TIMESTAMP WITHOUT TIME ZONE,
    reject_reason VARCHAR(300),
    CONSTRAINT fk_trip_claims_trip FOREIGN KEY (trip_id) REFERENCES trips(id),
    CONSTRAINT fk_trip_claims_driver FOREIGN KEY (driver_user_id) REFERENCES users(id),
    CONSTRAINT fk_trip_claims_company FOREIGN KEY (company_id) REFERENCES companies(id),
    CONSTRAINT fk_trip_claims_decided_by FOREIGN KEY (decided_by) REFERENCES users(id)
);

CREATE INDEX IF NOT EXISTS idx_trip_claims_trip_id ON trip_claims (trip_id);
CREATE INDEX IF NOT EXISTS idx_trip_claims_driver_user_id ON trip_claims (driver_user_id);
CREATE INDEX IF NOT EXISTS idx_trip_claims_company_id ON trip_claims (company_id);
