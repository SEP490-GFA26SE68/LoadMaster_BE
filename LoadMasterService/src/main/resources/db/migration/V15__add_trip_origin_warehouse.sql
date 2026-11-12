
ALTER TABLE trips
    ADD COLUMN origin_warehouse_id BIGINT;

ALTER TABLE trips
    ADD CONSTRAINT fk_trips_origin_warehouse
        FOREIGN KEY (origin_warehouse_id)
            REFERENCES company_warehouses(id)
            ON DELETE RESTRICT;

CREATE INDEX idx_trips_origin_warehouse
    ON trips(origin_warehouse_id);
