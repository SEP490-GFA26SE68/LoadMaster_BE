
-- HC-12: Axle Load configuration
-- Coordinates are in cm, relative to the front
-- inner wall of the cargo compartment (X = 0).
-- Axle loads are in kg.

ALTER TABLE vehicle_types
    ADD COLUMN front_axle_x NUMERIC(10, 2),
    ADD COLUMN rear_axle_x NUMERIC(10, 2),
    ADD COLUMN empty_front_axle_load_kg NUMERIC(10, 2),
    ADD COLUMN empty_rear_axle_load_kg NUMERIC(10, 2);

-- Front axle may be located before cargo X = 0,
-- so front_axle_x is allowed to be negative.

ALTER TABLE vehicle_types
    ADD CONSTRAINT chk_vehicle_types_axle_positions
    CHECK (
        front_axle_x IS NULL
        OR rear_axle_x IS NULL
        OR rear_axle_x > front_axle_x
    );

ALTER TABLE vehicle_types
    ADD CONSTRAINT chk_vehicle_types_empty_axle_loads
    CHECK (
        (empty_front_axle_load_kg IS NULL
            OR empty_front_axle_load_kg >= 0)
        AND
        (empty_rear_axle_load_kg IS NULL
            OR empty_rear_axle_load_kg >= 0)
    );
