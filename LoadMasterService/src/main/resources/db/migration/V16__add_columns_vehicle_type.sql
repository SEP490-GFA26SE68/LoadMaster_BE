
ALTER TABLE public.vehicle_types
    ADD COLUMN IF NOT EXISTS door_width INTEGER,
    ADD COLUMN IF NOT EXISTS door_height INTEGER;
