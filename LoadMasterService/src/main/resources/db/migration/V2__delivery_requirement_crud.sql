ALTER TABLE packages
    ADD COLUMN IF NOT EXISTS handling_class VARCHAR(20) NOT NULL DEFAULT 'STANDARD';

ALTER TABLE packages
    DROP CONSTRAINT IF EXISTS ck_packages_handling_class;

ALTER TABLE packages
    ADD CONSTRAINT ck_packages_handling_class
        CHECK (handling_class IN ('STANDARD', 'FRAGILE', 'REFRIGERATED', 'HAZARDOUS', 'HIGH_VALUE'));

CREATE TABLE IF NOT EXISTS delivery_requirements (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES companies(id),
    destination VARCHAR(255) NOT NULL,
    destination_lat DECIMAL(10, 7),
    destination_lng DECIMAL(10, 7),
    deadline TIMESTAMP NOT NULL,
    priority VARCHAR(20) NOT NULL DEFAULT 'NORMAL',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_by BIGINT REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_delivery_requirements_priority
        CHECK (priority IN ('LOW', 'NORMAL', 'HIGH', 'URGENT')),
    CONSTRAINT ck_delivery_requirements_status
        CHECK (status IN ('PENDING', 'ASSIGNED', 'IN_TRIP'))
);

CREATE TABLE IF NOT EXISTS delivery_requirement_packages (
    delivery_requirement_id BIGINT NOT NULL
        REFERENCES delivery_requirements(id) ON DELETE CASCADE,
    package_id BIGINT NOT NULL REFERENCES packages(id),
    CONSTRAINT pk_delivery_requirement_packages
        PRIMARY KEY (delivery_requirement_id, package_id)
);

CREATE INDEX IF NOT EXISTS idx_delivery_requirements_company_status_deadline
    ON delivery_requirements(company_id, status, deadline);

CREATE INDEX IF NOT EXISTS idx_delivery_requirement_packages_package
    ON delivery_requirement_packages(package_id);
