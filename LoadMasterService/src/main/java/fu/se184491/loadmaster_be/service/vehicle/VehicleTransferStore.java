package fu.se184491.loadmaster_be.service.vehicle;

import fu.se184491.loadmaster_be.exception.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.data.domain.*;
import java.util.*;

/** All mutations require the vehicle row lock first. Never takes a Trip lock. */
@Repository @RequiredArgsConstructor
public class VehicleTransferStore {
    private final JdbcTemplate sql;
    public Map<String,Object> find(long id,long company,boolean lock) {
        return sql.queryForList("SELECT * FROM vehicle_warehouse_transfers WHERE id=? AND company_id=?"+(lock?" FOR UPDATE":""),id,company)
            .stream().findFirst().orElseThrow(()->new AppException(ErrorCode.RESOURCE_NOT_FOUND));
    }
    public void actor(long id,long company,String role) {
        if(sql.queryForList("SELECT id FROM users WHERE id=? AND company_id=? AND user_role_type=? AND status='ACTIVE' FOR SHARE",id,company,role).isEmpty())
            throw new AppException(ErrorCode.UNAUTHORIZED);
    }
    public Map<String,Object> byKey(long company,UUID key) {
        return sql.queryForList("SELECT * FROM vehicle_warehouse_transfers WHERE company_id=? AND request_key=?",company,key).stream().findFirst().orElse(null);
    }
    public long create(long company,long vehicle,long source,long destination,long actor,UUID key,String reason) {
        return sql.queryForObject("INSERT INTO vehicle_warehouse_transfers(company_id,vehicle_id,source_warehouse_id,destination_warehouse_id,requested_by,request_key,reason) VALUES(?,?,?,?,?,?,?) RETURNING id",Long.class,company,vehicle,source,destination,actor,key,reason);
    }
    public boolean actionKeyAvailable(long company,long id,boolean arrival,UUID key) {
        String column=arrival?"arrival_key":"departure_key";
        return !Boolean.TRUE.equals(sql.queryForObject("SELECT EXISTS(SELECT 1 FROM vehicle_warehouse_transfers WHERE company_id=? AND "+column+"=? AND id<>?)",Boolean.class,company,key,id));
    }
    public void transition(long id,String next,long actor,UUID key) {
        String fields=switch(next) {
            case "IN_TRANSIT" -> "departed_by=?,departed_at=clock_timestamp(),departure_key=?";
            case "COMPLETED" -> "arrived_by=?,arrived_at=clock_timestamp(),arrival_key=?";
            case "CANCELLED" -> "cancelled_by=?,cancelled_at=clock_timestamp()";
            default -> throw new IllegalArgumentException("Unsupported transfer transition");
        };
        if(key==null) sql.update("UPDATE vehicle_warehouse_transfers SET status=?,version=version+1,"+fields+" WHERE id=?",next,actor,id);
        else sql.update("UPDATE vehicle_warehouse_transfers SET status=?,version=version+1,"+fields+" WHERE id=?",next,actor,key,id);
    }
    public List<Map<String,Object>> warehouses(long company) {
        return sql.queryForList("SELECT id,warehouse_name AS name FROM company_warehouses WHERE company_id=? AND is_active ORDER BY warehouse_name,id",company);
    }
    public Page<Map<String,Object>> candidates(long company,long warehouse,int page) {
        String where=" FROM vehicles v JOIN vehicle_current_presence p ON p.vehicle_id=v.id AND p.company_id=v.company_id WHERE v.company_id=? AND v.status='AVAILABLE' AND p.status='AT_WAREHOUSE' AND p.current_warehouse_id=? AND NOT EXISTS(SELECT 1 FROM trips t WHERE t.vehicle_id=v.id AND t.status IN ('LOADING','IN_TRANSIT')) AND NOT EXISTS(SELECT 1 FROM vehicle_warehouse_transfers t WHERE t.vehicle_id=v.id AND t.status IN ('REQUESTED','IN_TRANSIT'))";
        long count=sql.queryForObject("SELECT count(*)"+where,Long.class,company,warehouse);
        return new PageImpl<>(sql.queryForList("SELECT v.id,v.name,v.license_plate"+where+" ORDER BY v.id LIMIT 50 OFFSET ?",company,warehouse,(long)page*50),PageRequest.of(page,50),count);
    }
    public Page<Map<String,Object>> list(long company,Long warehouse,String status,int page) {
        String where=" WHERE t.company_id=? AND (?::bigint IS NULL OR t.source_warehouse_id=? OR t.destination_warehouse_id=?) AND (?='' OR t.status=?)";
        Object[] args={company,warehouse,warehouse,warehouse,status,status};
        long count=sql.queryForObject("SELECT count(*) FROM vehicle_warehouse_transfers t"+where,Long.class,args);
        var all=new ArrayList<Object>(Arrays.asList(args));all.add((long)page*50);
        var rows=sql.queryForList("SELECT t.*,v.name AS vehicle_name,v.license_plate,s.warehouse_name AS source_name,d.warehouse_name AS destination_name FROM vehicle_warehouse_transfers t JOIN vehicles v ON v.id=t.vehicle_id JOIN company_warehouses s ON s.id=t.source_warehouse_id JOIN company_warehouses d ON d.id=t.destination_warehouse_id"+where+" ORDER BY t.id DESC LIMIT 50 OFFSET ?",all.toArray());
        rows.forEach(this::timestamps);
        return new PageImpl<>(rows,PageRequest.of(page,50),count);
    }
    public Map<String,Object> timestamps(Map<String,Object> row) {
        row.replaceAll((k,v)->v instanceof java.sql.Timestamp t?t.toInstant().toString():v);
        return row;
    }
    public void audit(long id,long company,long actor,String old,String next) {
        sql.update("INSERT INTO audit_logs(user_id,company_id,action_type,entity_name,entity_id,old_values,new_values,created_at) VALUES(?,?,'VEHICLE_TRANSFER','VehicleTransfer',?,jsonb_build_object('status',?::text),jsonb_build_object('status',?::text),clock_timestamp())",actor,company,Long.toString(id),old,next);
    }
}
