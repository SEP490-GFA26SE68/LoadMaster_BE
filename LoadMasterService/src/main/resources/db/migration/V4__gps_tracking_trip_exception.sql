CREATE TABLE IF NOT EXISTS gps_tracking (
    id              BIGSERIAL PRIMARY KEY,
    trip_id         BIGINT NOT NULL REFERENCES trips(id),
    latitude        DECIMAL(10, 7) NOT NULL,
    longitude       DECIMAL(10, 7) NOT NULL,
    speed_kmh       DOUBLE PRECISION,
    heading         DOUBLE PRECISION,
    source          VARCHAR(20) NOT NULL DEFAULT 'GPS',
    recorded_at     TIMESTAMP NOT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_gps_tracking_trip_id ON gps_tracking(trip_id);
CREATE INDEX IF NOT EXISTS idx_gps_tracking_recorded_at ON gps_tracking(recorded_at);
CREATE INDEX IF NOT EXISTS idx_gps_tracking_trip_recorded ON gps_tracking(trip_id, recorded_at DESC);

CREATE TABLE IF NOT EXISTS trip_exceptions (
    id                  BIGSERIAL PRIMARY KEY,
    trip_id             BIGINT NOT NULL REFERENCES trips(id),
    type                VARCHAR(40) NOT NULL,
    description         TEXT,
    delay_minutes       INT NOT NULL DEFAULT 0,
    status              VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    stop_number         INT,
    reported_at         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reported_by         BIGINT REFERENCES users(id),
    resolved_at         TIMESTAMP,
    resolved_by         BIGINT REFERENCES users(id),
    resolution_note     TEXT,
    escalation_reason   VARCHAR(40),
    escalated_at        TIMESTAMP,
    escalated_by        BIGINT REFERENCES users(id),
    CONSTRAINT ck_trip_exceptions_status CHECK (status IN ('OPEN', 'ESCALATED', 'RESOLVED'))
);

CREATE INDEX IF NOT EXISTS idx_trip_exceptions_trip_id ON trip_exceptions(trip_id);

CREATE TABLE IF NOT EXISTS eta_risk_alerts (
    id              BIGSERIAL PRIMARY KEY,
    trip_id         BIGINT NOT NULL REFERENCES trips(id),
    stop_id         BIGINT,
    stop_number     INT NOT NULL,
    status          VARCHAR(20) NOT NULL,
    eta             TIMESTAMP NOT NULL,
    deadline        TIMESTAMP NOT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_eta_risk_alerts_status CHECK (status IN ('AT_RISK', 'MISSED'))
);

CREATE INDEX IF NOT EXISTS idx_eta_risk_alerts_trip_id ON eta_risk_alerts(trip_id);

CREATE TABLE IF NOT EXISTS trip_reroutes (
    id                  BIGSERIAL PRIMARY KEY,
    trip_id             BIGINT NOT NULL REFERENCES trips(id),
    stop_id             BIGINT,
    stop_number         INT,
    proposal_json       JSON NOT NULL,
    selected_index      INT,
    confirmed_json      JSON,
    requested_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    confirmed_at        TIMESTAMP,
    confirmed_by        BIGINT REFERENCES users(id),
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_trip_reroutes_trip_id ON trip_reroutes(trip_id);
