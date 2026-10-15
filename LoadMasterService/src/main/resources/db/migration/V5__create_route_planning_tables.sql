-- ========================================================
-- V5: TẠO CÁC BẢNG QUẢN LÝ TUYẾN ĐƯỜNG (PHƯƠNG ÁN 2)
-- ========================================================

-- 1. Bảng route_plans
CREATE TABLE IF NOT EXISTS route_plans (
    id BIGSERIAL PRIMARY KEY,
    trip_id BIGINT NOT NULL,
    plan_version INT NOT NULL DEFAULT 1,
    total_distance_meters INT,
    total_duration_seconds INT,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_route_plans_trip FOREIGN KEY (trip_id) 
        REFERENCES trips(id) ON DELETE CASCADE
);

CREATE INDEX idx_route_plans_trip_id ON route_plans(trip_id);
CREATE INDEX idx_route_plans_active ON route_plans(trip_id, is_active);

-- 2. Bảng route_legs
CREATE TABLE IF NOT EXISTS route_legs (
    id BIGSERIAL PRIMARY KEY,
    route_plan_id BIGINT NOT NULL,
    leg_sequence INT NOT NULL,
    start_stop_id BIGINT,
    end_stop_id BIGINT NOT NULL,
    distance_meters INT NOT NULL,
    duration_seconds INT NOT NULL,
    polyline TEXT,
    CONSTRAINT fk_route_legs_plan FOREIGN KEY (route_plan_id) 
        REFERENCES route_plans(id) ON DELETE CASCADE,
    CONSTRAINT fk_route_legs_start_stop FOREIGN KEY (start_stop_id) 
        REFERENCES delivery_stops(id) ON DELETE SET NULL,
    CONSTRAINT fk_route_legs_end_stop FOREIGN KEY (end_stop_id) 
        REFERENCES delivery_stops(id) ON DELETE RESTRICT,
    CONSTRAINT uk_route_plan_leg_seq UNIQUE (route_plan_id, leg_sequence)
);

CREATE INDEX idx_route_legs_plan_id ON route_legs(route_plan_id);

-- 3. Bảng route_suggestions
CREATE TABLE IF NOT EXISTS route_suggestions (
    id BIGSERIAL PRIMARY KEY,
    route_leg_id BIGINT NOT NULL,
    trip_exception_id BIGINT,
    suggestion_type VARCHAR(30) NOT NULL,
    duration_diff_seconds INT,
    distance_diff_meters INT,
    is_accepted BOOLEAN NOT NULL DEFAULT FALSE,
    proposed_polyline TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_route_suggestions_leg FOREIGN KEY (route_leg_id) 
        REFERENCES route_legs(id) ON DELETE CASCADE
);

CREATE INDEX idx_route_suggestions_leg_id ON route_suggestions(route_leg_id);
