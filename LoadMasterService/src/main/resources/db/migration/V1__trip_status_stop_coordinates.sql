ALTER TABLE IF EXISTS delivery_stops
    ADD COLUMN IF NOT EXISTS latitude DECIMAL(10, 7),
    ADD COLUMN IF NOT EXISTS longitude DECIMAL(10, 7),
    ADD COLUMN IF NOT EXISTS planned_arrival TIMESTAMP,
    ADD COLUMN IF NOT EXISTS actual_arrival TIMESTAMP;

ALTER TABLE IF EXISTS trips
    ADD COLUMN IF NOT EXISTS route_plan JSON,
    ADD COLUMN IF NOT EXISTS handling_class_lock VARCHAR(20),
    ADD COLUMN IF NOT EXISTS override_reason TEXT;

DO $$
BEGIN
    IF to_regclass('public.delivery_stops') IS NOT NULL THEN
        UPDATE delivery_stops
        SET status = CASE
            WHEN status IN ('PENDING', 'ARRIVED', 'COMPLETED') THEN status
            WHEN status = 'SKIPPED' THEN 'COMPLETED'
            ELSE 'PENDING'
        END;

        ALTER TABLE delivery_stops
            ALTER COLUMN status TYPE VARCHAR(20),
            ALTER COLUMN status SET DEFAULT 'PENDING',
            ALTER COLUMN status SET NOT NULL;

        ALTER TABLE delivery_stops
            DROP CONSTRAINT IF EXISTS ck_delivery_stops_status;
        ALTER TABLE delivery_stops
            ADD CONSTRAINT ck_delivery_stops_status
                CHECK (status IN ('PENDING', 'ARRIVED', 'COMPLETED'));
    END IF;
END
$$;

DO $$
BEGIN
    IF to_regclass('public.trips') IS NOT NULL THEN
        UPDATE trips
        SET status = CASE
            WHEN status IN ('DRAFT', 'PLANNED', 'LOADING', 'IN_TRANSIT', 'DELIVERED', 'CANCELLED') THEN status
            WHEN status IN ('OPTIMIZED', 'APPROVED') THEN 'PLANNED'
            WHEN status = 'READY_FOR_DELIVERY' THEN 'IN_TRANSIT'
            WHEN status = 'COMPLETED' THEN 'DELIVERED'
            ELSE 'DRAFT'
        END;

        ALTER TABLE trips
            ALTER COLUMN status TYPE VARCHAR(20),
            ALTER COLUMN status SET DEFAULT 'DRAFT',
            ALTER COLUMN status SET NOT NULL;

        ALTER TABLE trips
            DROP CONSTRAINT IF EXISTS ck_trips_status;
        ALTER TABLE trips
            ADD CONSTRAINT ck_trips_status
                CHECK (status IN ('DRAFT', 'PLANNED', 'LOADING', 'IN_TRANSIT', 'DELIVERED', 'CANCELLED'));
    END IF;
END
$$;
