-- Bước chỉ dẫn Goong (maneuver) của từng RouteLeg — tài xế chỉ đường trong app.
ALTER TABLE route_legs ADD COLUMN IF NOT EXISTS steps JSON;
