-- Vehicle warehouse transfers were queried by VehiclePresenceStore after the
-- vehicle-transfer merge, but the table only existed as a reviewed test SQL
-- fixture. Local /api/vehicles/departure-choices then failed with
-- relation "vehicle_warehouse_transfers" does not exist.

CREATE TABLE vehicle_warehouse_transfers (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    company_id bigint NOT NULL REFERENCES companies(id),
    vehicle_id bigint NOT NULL,
    source_warehouse_id bigint NOT NULL,
    destination_warehouse_id bigint NOT NULL,
    requested_by bigint NOT NULL,
    requested_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    request_key uuid NOT NULL,
    reason varchar(500) NOT NULL CHECK (length(trim(reason)) BETWEEN 1 AND 500),
    status varchar(20) NOT NULL DEFAULT 'REQUESTED' CHECK (status IN ('REQUESTED', 'IN_TRANSIT', 'COMPLETED', 'CANCELLED')),
    departed_by bigint,
    departed_at timestamptz,
    departure_key uuid,
    arrived_by bigint,
    arrived_at timestamptz,
    arrival_key uuid,
    cancelled_by bigint,
    cancelled_at timestamptz,
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    UNIQUE (company_id, request_key),
    UNIQUE (company_id, departure_key),
    UNIQUE (company_id, arrival_key),
    CHECK (source_warehouse_id <> destination_warehouse_id),
    CHECK ((departed_by IS NULL) = (departed_at IS NULL) AND (departed_by IS NULL) = (departure_key IS NULL)),
    CHECK ((arrived_by IS NULL) = (arrived_at IS NULL) AND (arrived_by IS NULL) = (arrival_key IS NULL)),
    CHECK ((cancelled_by IS NULL) = (cancelled_at IS NULL)),
    CHECK (
        (status = 'REQUESTED' AND departed_at IS NULL AND arrived_at IS NULL AND cancelled_at IS NULL)
        OR (status = 'IN_TRANSIT' AND departed_at IS NOT NULL AND arrived_at IS NULL AND cancelled_at IS NULL)
        OR (status = 'COMPLETED' AND departed_at IS NOT NULL AND arrived_at IS NOT NULL AND cancelled_at IS NULL)
        OR (status = 'CANCELLED' AND departed_at IS NULL AND arrived_at IS NULL AND cancelled_at IS NOT NULL)
    ),
    CHECK (departed_at IS NULL OR departed_at >= requested_at),
    CHECK (arrived_at IS NULL OR arrived_at >= departed_at),
    CHECK (cancelled_at IS NULL OR cancelled_at >= requested_at),
    FOREIGN KEY (vehicle_id, company_id) REFERENCES vehicles (id, company_id),
    FOREIGN KEY (source_warehouse_id, company_id) REFERENCES company_warehouses (id, company_id),
    FOREIGN KEY (destination_warehouse_id, company_id) REFERENCES company_warehouses (id, company_id),
    FOREIGN KEY (requested_by, company_id) REFERENCES users (id, company_id),
    FOREIGN KEY (departed_by, company_id) REFERENCES users (id, company_id),
    FOREIGN KEY (arrived_by, company_id) REFERENCES users (id, company_id),
    FOREIGN KEY (cancelled_by, company_id) REFERENCES users (id, company_id)
);

CREATE UNIQUE INDEX vehicle_one_active_transfer
    ON vehicle_warehouse_transfers (vehicle_id)
    WHERE status IN ('REQUESTED', 'IN_TRANSIT');

CREATE INDEX vehicle_transfer_company_history
    ON vehicle_warehouse_transfers (company_id, id DESC);

CREATE FUNCTION vehicle_transfer_transition_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.company_id, NEW.vehicle_id, NEW.source_warehouse_id, NEW.destination_warehouse_id, NEW.requested_by, NEW.requested_at, NEW.request_key, NEW.reason)
        IS DISTINCT FROM (OLD.company_id, OLD.vehicle_id, OLD.source_warehouse_id, OLD.destination_warehouse_id, OLD.requested_by, OLD.requested_at, OLD.request_key, OLD.reason)
        OR NEW.version <> OLD.version + 1
        OR NOT ((OLD.status = 'REQUESTED' AND NEW.status IN ('IN_TRANSIT', 'CANCELLED')) OR (OLD.status = 'IN_TRANSIT' AND NEW.status = 'COMPLETED'))
        OR (OLD.status = 'IN_TRANSIT' AND (NEW.departed_by, NEW.departed_at, NEW.departure_key) IS DISTINCT FROM (OLD.departed_by, OLD.departed_at, OLD.departure_key))
    THEN
        RAISE EXCEPTION 'Invalid transfer transition or request identity change';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER vehicle_transfer_transition
    BEFORE UPDATE ON vehicle_warehouse_transfers
    FOR EACH ROW EXECUTE FUNCTION vehicle_transfer_transition_guard();

ALTER TABLE vehicle_warehouse_transfers ENABLE ROW LEVEL SECURITY;

REVOKE ALL ON TABLE vehicle_warehouse_transfers FROM PUBLIC;
REVOKE ALL ON SEQUENCE vehicle_warehouse_transfers_id_seq FROM PUBLIC;

DO $$
DECLARE
    r text;
BEGIN
    FOREACH r IN ARRAY ARRAY['anon', 'authenticated'] LOOP
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r) THEN
            EXECUTE format('REVOKE ALL ON TABLE vehicle_warehouse_transfers FROM %I', r);
            EXECUTE format('REVOKE ALL ON SEQUENCE vehicle_warehouse_transfers_id_seq FROM %I', r);
        END IF;
    END LOOP;
END
$$;
