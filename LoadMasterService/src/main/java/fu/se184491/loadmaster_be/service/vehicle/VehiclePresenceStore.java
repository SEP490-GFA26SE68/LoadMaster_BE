package fu.se184491.loadmaster_be.service.vehicle;

import fu.se184491.loadmaster_be.exception.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.*;
import java.util.*;

/** JDBC shares the JPA transaction/data source. Lock order: Trip (if any), Vehicle, presence.
 * No operation holding a Vehicle lock requests a Trip lock. Missing presence means UNKNOWN. */
@Repository @RequiredArgsConstructor
public class VehiclePresenceStore {
    private final JdbcTemplate sql;
    public record Presence(Long vehicleId, Long companyId, Long warehouseId, String status,
                           Instant observedAt, Long actorId, String actorName, long version) {}
    public record Event(long id, long vehicleId, long companyId, Long warehouseId, String eventType,
                        long actorId, String reference, String previousStatus, String newStatus, Instant recordedAt) {}
    public Map<String,Object> lockVehicle(long vehicle, long company) {
        var rows=sql.queryForList("SELECT id,status FROM vehicles WHERE id=? AND company_id=? FOR UPDATE",vehicle,company);
        if(rows.isEmpty()) throw new AppException(ErrorCode.VEHICLE_NOT_FOUND);
        return rows.getFirst();
    }
    public Map<String,Object> lockTrip(long trip,long company) {
        return sql.queryForList("SELECT id,vehicle_id,origin_warehouse_id,status FROM trips WHERE id=? AND company_id=? FOR UPDATE",trip,company)
            .stream().findFirst().orElseThrow(()->new AppException(ErrorCode.TRIP_NOT_FOUND));
    }
    public boolean warehouse(long warehouse,long company) {
        return Boolean.TRUE.equals(sql.queryForObject("SELECT EXISTS(SELECT 1 FROM company_warehouses WHERE id=? AND company_id=? AND is_active)",Boolean.class,warehouse,company));
    }
    public boolean lockWarehouse(long warehouse,long company) {
        return !sql.queryForList("SELECT id FROM company_warehouses WHERE id=? AND company_id=? AND is_active FOR SHARE",warehouse,company).isEmpty();
    }
    public boolean hasReferences(long vehicle,long company) {
        return Boolean.TRUE.equals(sql.queryForObject("SELECT EXISTS(SELECT 1 FROM vehicle_warehouse_transfers WHERE vehicle_id=? AND company_id=?) OR EXISTS(SELECT 1 FROM vehicle_current_presence WHERE vehicle_id=? AND company_id=?) OR EXISTS(SELECT 1 FROM vehicle_presence_events WHERE vehicle_id=? AND company_id=?) OR EXISTS(SELECT 1 FROM vehicle_presence_checkin_requests WHERE vehicle_id=? AND company_id=?)",Boolean.class,vehicle,company,vehicle,company,vehicle,company,vehicle,company));
    }
    public boolean assigned(long actor,long company,long warehouse) {
        return Boolean.TRUE.equals(sql.queryForObject("SELECT EXISTS(SELECT 1 FROM warehouse_staff_assignments a JOIN users u ON u.id=a.user_id AND u.company_id=a.company_id WHERE a.user_id=? AND a.company_id=? AND a.warehouse_id=? AND a.active AND u.user_role_type='WAREHOUSE_WORKER' AND u.status='ACTIVE')",Boolean.class,actor,company,warehouse));
    }
    public void lockRequest(long company,String reference) {
        sql.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",company+":"+reference);
    }
    public boolean lockAssignment(long actor,long company,long warehouse) {
        return !sql.queryForList("SELECT a.user_id FROM warehouse_staff_assignments a JOIN users u ON u.id=a.user_id AND u.company_id=a.company_id JOIN company_warehouses w ON w.id=a.warehouse_id AND w.company_id=a.company_id WHERE a.user_id=? AND a.company_id=? AND a.warehouse_id=? AND a.active AND w.is_active AND u.user_role_type='WAREHOUSE_WORKER' AND u.status='ACTIVE' FOR SHARE OF a,u,w",actor,company,warehouse).isEmpty();
    }
    public Map<String,Object> receipt(long company,String key) {
        return sql.queryForList("SELECT * FROM vehicle_presence_checkin_requests WHERE company_id=? AND request_key=?",company,key).stream().findFirst().orElse(null);
    }
    public void receipt(long company,String key,long vehicle,long warehouse,long actor) {
        sql.update("INSERT INTO vehicle_presence_checkin_requests(company_id,request_key,vehicle_id,warehouse_id,actor_user_id) VALUES(?,?,?,?,?)",company,key,vehicle,warehouse,actor);
    }
    public boolean owned(long company,long vehicle) {
        return Boolean.TRUE.equals(sql.queryForObject("SELECT EXISTS(SELECT 1 FROM vehicles WHERE id=? AND company_id=?)",Boolean.class,vehicle,company));
    }
    public boolean busy(long vehicle,Long excludingTrip) {
        return activeTransfer(vehicle) || tripBusy(vehicle,excludingTrip);
    }
    public boolean activeTransfer(long vehicle) {
        return Boolean.TRUE.equals(sql.queryForObject("SELECT EXISTS(SELECT 1 FROM vehicle_warehouse_transfers WHERE vehicle_id=? AND status IN ('REQUESTED','IN_TRANSIT'))",Boolean.class,vehicle));
    }
    public boolean tripBusy(long vehicle,Long excludingTrip) {
        return Boolean.TRUE.equals(sql.queryForObject("SELECT EXISTS(SELECT 1 FROM trips WHERE vehicle_id=? AND status IN ('LOADING','IN_TRANSIT') AND (?::bigint IS NULL OR id<>?))",Boolean.class,vehicle,excludingTrip,excludingTrip));
    }
    public Presence presence(long vehicle,long company) {
        var rows=sql.query("SELECT p.*,u.full_name actor_name FROM vehicle_current_presence p LEFT JOIN users u ON u.id=p.actor_user_id WHERE p.vehicle_id=? AND p.company_id=?",(rs,n)->new Presence(rs.getLong("vehicle_id"),rs.getLong("company_id"),(Long)rs.getObject("current_warehouse_id"),rs.getString("status"),rs.getTimestamp("observed_at").toInstant(),(Long)rs.getObject("actor_user_id"),rs.getString("actor_name"),rs.getLong("version")),vehicle,company);
        return rows.isEmpty()?new Presence(vehicle,company,null,"UNKNOWN",null,null,null,0):rows.getFirst();
    }
    /** The vehicle's LOADING trip leaving from this warehouse, if any. No lock: callers lock the Trip, then the Vehicle. */
    public Long loadingTripFrom(long vehicle,long company,long warehouse) {
        return sql.queryForList("SELECT id FROM trips WHERE vehicle_id=? AND company_id=? AND status='LOADING' AND origin_warehouse_id=? ORDER BY id LIMIT 1",Long.class,vehicle,company,warehouse).stream().findFirst().orElse(null);
    }
    public List<Event> events(long vehicle,long company) {
        return sql.query("SELECT * FROM vehicle_presence_events WHERE vehicle_id=? AND company_id=? ORDER BY id DESC LIMIT 100",(rs,n)->event(rs),vehicle,company);
    }
    private Event event(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Event(rs.getLong("id"),rs.getLong("vehicle_id"),rs.getLong("company_id"),(Long)rs.getObject("warehouse_id"),rs.getString("event_type"),rs.getLong("actor_user_id"),rs.getString("event_reference"),rs.getString("previous_status"),rs.getString("new_status"),rs.getTimestamp("recorded_at").toInstant());
    }
    public Optional<Event> event(long company,String ref) {
        return sql.query("SELECT * FROM vehicle_presence_events WHERE company_id=? AND event_reference=?",(rs,n)->event(rs),company,ref).stream().findFirst();
    }
    /** Called only with the vehicle locked; event + current state commit/rollback together. */
    public void change(long vehicle,long company,Long warehouse,String next,long actor,String ref,String kind) {
        var previous=presence(vehicle,company);
        Instant now=sql.queryForObject("SELECT clock_timestamp()",OffsetDateTime.class).toInstant();
        sql.update("INSERT INTO vehicle_presence_events(vehicle_id,company_id,warehouse_id,event_type,actor_user_id,event_reference,previous_status,new_status,previous_warehouse_id,new_warehouse_id,recorded_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",vehicle,company,warehouse,kind,actor,ref,previous.status(),next,previous.warehouseId(),"AT_WAREHOUSE".equals(next)?warehouse:null,java.sql.Timestamp.from(now));
        sql.update("INSERT INTO vehicle_current_presence(vehicle_id,company_id,current_warehouse_id,status,observed_at,actor_user_id,version) VALUES(?,?,?,?,?,?,1) ON CONFLICT(vehicle_id) DO UPDATE SET current_warehouse_id=EXCLUDED.current_warehouse_id,status=EXCLUDED.status,observed_at=EXCLUDED.observed_at,actor_user_id=EXCLUDED.actor_user_id,version=vehicle_current_presence.version+1",vehicle,company,"AT_WAREHOUSE".equals(next)?warehouse:null,next,java.sql.Timestamp.from(now),actor);
    }
    public List<Map<String,Object>> assignedWarehouses(long actor,long company) {
        return sql.queryForList("SELECT w.id,w.warehouse_name AS name FROM company_warehouses w JOIN warehouse_staff_assignments a ON a.warehouse_id=w.id AND a.company_id=w.company_id WHERE a.user_id=? AND a.company_id=? AND a.active AND w.is_active ORDER BY w.warehouse_name,w.id",actor,company);
    }
    public List<Map<String,Object>> search(long company,String search) {
        var rows=sql.queryForList("SELECT v.id,v.name,v.license_plate,CASE WHEN v.status IN ('MAINTENANCE','RETIRED') THEN v.status WHEN EXISTS(SELECT 1 FROM trips t WHERE t.vehicle_id=v.id AND t.status IN ('LOADING','IN_TRANSIT')) THEN 'IN_TRIP' ELSE v.status END AS status,COALESCE(p.status,'UNKNOWN') AS presence_status,p.current_warehouse_id,p.observed_at,u.full_name AS actor_name FROM vehicles v LEFT JOIN vehicle_current_presence p ON p.vehicle_id=v.id AND p.company_id=v.company_id LEFT JOIN users u ON u.id=p.actor_user_id WHERE v.company_id=? AND (v.license_plate ILIKE ? OR v.name ILIKE ?) ORDER BY v.id LIMIT 30",company,"%"+search+"%","%"+search+"%");
        rows.forEach(row -> { if(row.get("observed_at") instanceof java.sql.Timestamp stamp) row.put("observed_at",stamp.toInstant().toString()); });
        return rows;
    }
    /** Read-only, company-scoped catalogue; presence and operational availability remain independent. */
    public org.springframework.data.domain.Page<Map<String,Object>> catalog(long company,long warehouse,String search,int page,int size) {return catalog(company,warehouse,search,page,size,false);}
    public org.springframework.data.domain.Page<Map<String,Object>> catalog(long company,long warehouse,String search,int page,int size,boolean atWarehouse) {
        String like="%"+search.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";
        long count=sql.queryForObject("SELECT count(*) FROM vehicles v LEFT JOIN vehicle_current_presence p ON p.vehicle_id=v.id AND p.company_id=v.company_id WHERE v.company_id=? AND (v.license_plate ILIKE ? OR v.name ILIKE ?) AND (?=false OR (p.status='AT_WAREHOUSE' AND p.current_warehouse_id=?))",Long.class,company,like,like,atWarehouse,warehouse);
        var rows=sql.queryForList("SELECT v.id,v.name,v.license_plate,(SELECT tf.status FROM vehicle_warehouse_transfers tf WHERE tf.vehicle_id=v.id AND tf.status IN ('REQUESTED','IN_TRANSIT')) AS active_transfer_status,lt.id AS loading_trip_id,lt.trip_code AS loading_trip_code,CASE WHEN v.status IN ('MAINTENANCE','RETIRED') THEN v.status WHEN EXISTS(SELECT 1 FROM trips t WHERE t.vehicle_id=v.id AND t.status IN ('LOADING','IN_TRANSIT')) THEN 'IN_TRIP' ELSE v.status END AS status,COALESCE(p.status,'UNKNOWN') AS presence_status,p.current_warehouse_id,w.warehouse_name AS current_warehouse_name,p.observed_at,u.full_name AS actor_name FROM vehicles v LEFT JOIN vehicle_current_presence p ON p.vehicle_id=v.id AND p.company_id=v.company_id LEFT JOIN company_warehouses w ON w.id=p.current_warehouse_id AND w.company_id=v.company_id LEFT JOIN users u ON u.id=p.actor_user_id AND u.company_id=v.company_id LEFT JOIN LATERAL (SELECT t.id,t.trip_code FROM trips t WHERE t.vehicle_id=v.id AND t.company_id=v.company_id AND t.status='LOADING' AND t.origin_warehouse_id=? AND NOT EXISTS(SELECT 1 FROM trips other WHERE other.vehicle_id=v.id AND other.status IN ('LOADING','IN_TRANSIT') AND other.id<>t.id) ORDER BY t.id LIMIT 1) lt ON true WHERE v.company_id=? AND (v.license_plate ILIKE ? OR v.name ILIKE ?) AND (?=false OR (p.status='AT_WAREHOUSE' AND p.current_warehouse_id=?)) ORDER BY v.id LIMIT ? OFFSET ?",warehouse,company,like,like,atWarehouse,warehouse,size,(long)page*size);
        rows.forEach(row -> { if(row.get("observed_at") instanceof java.sql.Timestamp stamp) row.put("observed_at",stamp.toInstant().toString()); });
        return new org.springframework.data.domain.PageImpl<>(rows,org.springframework.data.domain.PageRequest.of(page,size),count);
    }
    public List<Long> eligibleIds(long company,long warehouse,Long trip,int page,int size) {
        return sql.queryForList("SELECT v.id FROM vehicles v JOIN vehicle_current_presence p ON p.vehicle_id=v.id AND p.company_id=v.company_id WHERE v.company_id=? AND NOT EXISTS(SELECT 1 FROM vehicle_warehouse_transfers tf WHERE tf.vehicle_id=v.id AND tf.status IN ('REQUESTED','IN_TRANSIT')) AND v.status='AVAILABLE' AND p.status='AT_WAREHOUSE' AND p.current_warehouse_id=? AND NOT EXISTS(SELECT 1 FROM trips t WHERE t.vehicle_id=v.id AND t.status IN ('LOADING','IN_TRANSIT') AND (?::bigint IS NULL OR t.id<>?)) ORDER BY v.id LIMIT ? OFFSET ?",Long.class,company,warehouse,trip,trip,size,(long)page*size);
    }
    public long eligibleCount(long company,long warehouse,Long trip) {
        return sql.queryForObject("SELECT count(*) FROM vehicles v JOIN vehicle_current_presence p ON p.vehicle_id=v.id AND p.company_id=v.company_id WHERE v.company_id=? AND NOT EXISTS(SELECT 1 FROM vehicle_warehouse_transfers tf WHERE tf.vehicle_id=v.id AND tf.status IN ('REQUESTED','IN_TRANSIT')) AND v.status='AVAILABLE' AND p.status='AT_WAREHOUSE' AND p.current_warehouse_id=? AND NOT EXISTS(SELECT 1 FROM trips t WHERE t.vehicle_id=v.id AND t.status IN ('LOADING','IN_TRANSIT') AND (?::bigint IS NULL OR t.id<>?))",Long.class,company,warehouse,trip,trip);
    }
}
