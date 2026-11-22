-- PROPOSAL ONLY. Apply manually after review/backup with application writers stopped.
-- Does not backfill warehouse, change legacy vehicle/trip/user/credit data, or enable Flyway.
BEGIN;
SET LOCAL lock_timeout = '5s';
DO $$ BEGIN
 IF to_regclass('public.vehicle_current_presence') IS NOT NULL THEN
   RAISE EXCEPTION 'Presence schema already exists: inspect its definition before applying';
 END IF;
END $$;
-- Composite tenant keys: additive unique indexes, not new primary keys.
CREATE UNIQUE INDEX IF NOT EXISTS uq_vehicle_presence_vehicle_company ON vehicles(id,company_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_vehicle_presence_warehouse_company ON company_warehouses(id,company_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_vehicle_presence_user_company ON users(id,company_id);
CREATE TABLE warehouse_staff_assignments (
 user_id bigint NOT NULL, company_id bigint NOT NULL, warehouse_id bigint NOT NULL,
 active boolean NOT NULL DEFAULT true,
 PRIMARY KEY(user_id,warehouse_id),
 FOREIGN KEY(user_id,company_id) REFERENCES users(id,company_id),
 FOREIGN KEY(warehouse_id,company_id) REFERENCES company_warehouses(id,company_id)
);
CREATE TABLE vehicle_current_presence (
 vehicle_id bigint PRIMARY KEY, company_id bigint NOT NULL,
 current_warehouse_id bigint, status varchar(20) NOT NULL DEFAULT 'UNKNOWN',
 observed_at timestamptz NOT NULL DEFAULT clock_timestamp(), actor_user_id bigint NOT NULL,
 version bigint NOT NULL DEFAULT 1 CHECK(version>0),
 FOREIGN KEY(vehicle_id,company_id) REFERENCES vehicles(id,company_id),
 FOREIGN KEY(current_warehouse_id,company_id) REFERENCES company_warehouses(id,company_id),
 FOREIGN KEY(actor_user_id,company_id) REFERENCES users(id,company_id),
 CHECK((status='AT_WAREHOUSE' AND current_warehouse_id IS NOT NULL) OR
       (status IN ('UNKNOWN','IN_TRANSIT') AND current_warehouse_id IS NULL))
);
CREATE INDEX idx_vehicle_presence_choices ON vehicle_current_presence(company_id,current_warehouse_id,vehicle_id) WHERE status='AT_WAREHOUSE';
CREATE TABLE vehicle_presence_events (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 vehicle_id bigint NOT NULL, company_id bigint NOT NULL, warehouse_id bigint NOT NULL,
 event_type varchar(20) NOT NULL CHECK(event_type IN ('CHECK_IN','CHECK_OUT')),
 actor_user_id bigint NOT NULL, event_reference varchar(128) NOT NULL,
 previous_status varchar(20) NOT NULL CHECK(previous_status IN ('UNKNOWN','AT_WAREHOUSE','IN_TRANSIT')),
 new_status varchar(20) NOT NULL CHECK(new_status IN ('UNKNOWN','AT_WAREHOUSE','IN_TRANSIT')),
 previous_warehouse_id bigint, new_warehouse_id bigint,
 recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(company_id,event_reference),
 FOREIGN KEY(vehicle_id,company_id) REFERENCES vehicles(id,company_id),
 FOREIGN KEY(warehouse_id,company_id) REFERENCES company_warehouses(id,company_id),
 FOREIGN KEY(previous_warehouse_id,company_id) REFERENCES company_warehouses(id,company_id),
 FOREIGN KEY(new_warehouse_id,company_id) REFERENCES company_warehouses(id,company_id),
 FOREIGN KEY(actor_user_id,company_id) REFERENCES users(id,company_id),
 CHECK((previous_status='AT_WAREHOUSE')=(previous_warehouse_id IS NOT NULL)),
 CHECK((new_status='AT_WAREHOUSE')=(new_warehouse_id IS NOT NULL)),
 CHECK((event_type='CHECK_IN' AND previous_status IN ('UNKNOWN','IN_TRANSIT') AND new_status='AT_WAREHOUSE' AND new_warehouse_id=warehouse_id) OR
       (event_type='CHECK_OUT' AND previous_status='AT_WAREHOUSE' AND new_status='IN_TRANSIT' AND previous_warehouse_id=warehouse_id))
);
CREATE INDEX idx_vehicle_presence_history ON vehicle_presence_events(company_id,vehicle_id,id DESC);
-- Receipts also remember no-op check-ins; an old retry cannot check a departed vehicle back in.
CREATE TABLE vehicle_presence_checkin_requests (
 company_id bigint NOT NULL, request_key varchar(36) NOT NULL,
 vehicle_id bigint NOT NULL, warehouse_id bigint NOT NULL, actor_user_id bigint NOT NULL,
 recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(company_id,request_key),
 FOREIGN KEY(vehicle_id,company_id) REFERENCES vehicles(id,company_id),
 FOREIGN KEY(warehouse_id,company_id) REFERENCES company_warehouses(id,company_id),
 FOREIGN KEY(actor_user_id,company_id) REFERENCES users(id,company_id)
);
CREATE FUNCTION vehicle_presence_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Vehicle presence audit/receipts are append-only'; END $$;
CREATE TRIGGER vehicle_presence_events_immutable BEFORE UPDATE OR DELETE ON vehicle_presence_events FOR EACH ROW EXECUTE FUNCTION vehicle_presence_append_only();
CREATE TRIGGER vehicle_presence_requests_immutable BEFORE UPDATE OR DELETE ON vehicle_presence_checkin_requests FOR EACH ROW EXECUTE FUNCTION vehicle_presence_append_only();
-- Public REST roles must not bypass application authorization on these new public tables.
-- No policies are installed for anonymous/authenticated clients. The approved server DB role
-- needs explicit reviewed privileges and a server-only policy if it is not an owner/BYPASSRLS role.
ALTER TABLE warehouse_staff_assignments ENABLE ROW LEVEL SECURITY;
ALTER TABLE vehicle_current_presence ENABLE ROW LEVEL SECURITY;
ALTER TABLE vehicle_presence_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE vehicle_presence_checkin_requests ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON TABLE warehouse_staff_assignments,vehicle_current_presence,vehicle_presence_events,vehicle_presence_checkin_requests FROM PUBLIC;
REVOKE ALL ON SEQUENCE vehicle_presence_events_id_seq FROM PUBLIC;
DO $$ DECLARE public_role text; BEGIN
 FOREACH public_role IN ARRAY ARRAY['anon','authenticated'] LOOP
  IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname=public_role) THEN
   EXECUTE format('REVOKE ALL ON TABLE warehouse_staff_assignments,vehicle_current_presence,vehicle_presence_events,vehicle_presence_checkin_requests FROM %I',public_role);
   EXECUTE format('REVOKE ALL ON SEQUENCE vehicle_presence_events_id_seq FROM %I',public_role);
  END IF;
 END LOOP;
END $$;
COMMIT;
-- No INSERT from legacy trips: absence of a presence row is UNKNOWN by contract.
