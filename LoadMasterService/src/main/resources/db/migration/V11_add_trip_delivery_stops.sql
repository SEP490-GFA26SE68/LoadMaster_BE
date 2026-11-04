ALTER TABLE delivery_stops
    ADD COLUMN delivery_requirement_id BIGINT;

ALTER TABLE delivery_stops
    ADD CONSTRAINT fk_delivery_stops_requirement
        FOREIGN KEY (delivery_requirement_id)
            REFERENCES delivery_requirements(id);

CREATE UNIQUE INDEX uk_delivery_stops_requirement
    ON delivery_stops(delivery_requirement_id)
    WHERE delivery_requirement_id IS NOT NULL;