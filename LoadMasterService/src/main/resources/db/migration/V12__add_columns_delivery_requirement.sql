
ALTER TABLE delivery_requirements
    ADD COLUMN customer_id BIGINT,
    ADD COLUMN expected_package_count INTEGER,
    ADD COLUMN note VARCHAR(500);

ALTER TABLE delivery_requirements
    ADD CONSTRAINT fk_delivery_requirements_customer
        FOREIGN KEY (customer_id) REFERENCES customers(id),
    ADD CONSTRAINT chk_delivery_requirements_expected_package_count
        CHECK (expected_package_count IS NULL OR expected_package_count > 0);

CREATE INDEX idx_delivery_requirements_customer_id
    ON delivery_requirements(customer_id);
