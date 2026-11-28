-- Ba phương án tuyến A · B · C mỗi lần tối ưu (cùng kiểu load_plans.candidate_label).
ALTER TABLE route_plans
    ADD COLUMN IF NOT EXISTS run_id VARCHAR(36),
    ADD COLUMN IF NOT EXISTS candidate_label VARCHAR(1),
    ADD COLUMN IF NOT EXISTS objective VARCHAR(40),
    ADD COLUMN IF NOT EXISTS is_approved BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS proposal_json JSON;

CREATE INDEX IF NOT EXISTS idx_route_plans_run_id ON route_plans (trip_id, run_id);
