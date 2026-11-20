package fu.se184491.loadmaster_be.service.company;

import fu.se184491.loadmaster_be.constant.account.*;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.exception.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

/** Uses the same DataSource/transaction manager as check-in. Never locks a vehicle. */
@Service @RequiredArgsConstructor
public class WarehouseStaffAssignmentService {
    private final JdbcTemplate sql;
    private final CurrentUserService current;
    public record Staff(long userId, String fullName, String status) {}
    public record Assignment(long userId, String fullName, String status, boolean active) {}
    public record Change(long id, long actorId, String actorName, long userId,
                         Boolean oldActive, boolean newActive, Instant recordedAt) {}
    private User manager(boolean lock) {
        User u=current.getCurrentUser();
        if(u.getCompany()==null || u.getUserRoleType()!=UserRole.MANAGER || u.getStatus()!=UserStatus.ACTIVE)
            throw new AppException(ErrorCode.UNAUTHORIZED);
        // Re-read authoritative role/status; stale principal snapshots cannot administer assignments.
        var rows=sql.queryForList("SELECT id FROM users WHERE id=? AND company_id=? AND user_role_type='MANAGER' AND status='ACTIVE'"+(lock?" FOR SHARE":""),u.getId(),u.getCompany().getId());
        if(rows.isEmpty()) throw new AppException(ErrorCode.UNAUTHORIZED);
        return u;
    }
    private void warehouse(long company,long warehouse,boolean lock,boolean requireActive) {
        var rows=sql.queryForList("SELECT is_active FROM company_warehouses WHERE id=? AND company_id=?"+(lock?" FOR SHARE":""),warehouse,company);
        if(rows.isEmpty()) throw new AppException(ErrorCode.VEHICLE_WAREHOUSE_FORBIDDEN);
        if(requireActive && !Boolean.TRUE.equals(rows.getFirst().get("is_active")))
            throw new AppException(ErrorCode.WAREHOUSE_ASSIGNMENT_INVALID);
    }
    @Transactional(readOnly=true)
    public List<Staff> staff() {
        long company=manager(false).getCompany().getId();
        return sql.query("SELECT id,full_name,status FROM users WHERE company_id=? AND user_role_type='WAREHOUSE_WORKER' ORDER BY full_name,id",
            (r,n)->new Staff(r.getLong("id"),r.getString("full_name"),r.getString("status")),company);
    }
    @Transactional(readOnly=true)
    public List<Assignment> assignments(long warehouse) {
        long company=manager(false).getCompany().getId();warehouse(company,warehouse,false,false);
        return sql.query("SELECT a.user_id,u.full_name,u.status,a.active FROM warehouse_staff_assignments a JOIN users u ON u.id=a.user_id AND u.company_id=a.company_id WHERE a.company_id=? AND a.warehouse_id=? ORDER BY u.full_name,a.user_id",
            (r,n)->new Assignment(r.getLong("user_id"),r.getString("full_name"),r.getString("status"),r.getBoolean("active")),company,warehouse);
    }
    @Transactional
    public List<Assignment> change(long warehouse,long staff,boolean active) {
        User actor=manager(true);long company=actor.getCompany().getId();
        warehouse(company,warehouse,true,active);
        // Shared parent locks are compatible with check-in; only the assignment is updated exclusively.
        var users=sql.queryForList("SELECT user_role_type,status FROM users WHERE id=? AND company_id=? FOR SHARE",staff,company);
        if(users.isEmpty()) throw new AppException(ErrorCode.VEHICLE_WAREHOUSE_FORBIDDEN);
        if(active && (!"WAREHOUSE_WORKER".equals(users.getFirst().get("user_role_type")) || !"ACTIVE".equals(users.getFirst().get("status"))))
            throw new AppException(ErrorCode.WAREHOUSE_ASSIGNMENT_INVALID);
        // Serializes absent-row creation as well as revoke/reactivate. PK remains the final duplicate guard.
        sql.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))","warehouse-staff:"+company+":"+warehouse+":"+staff);
        var previous=sql.queryForList("SELECT active FROM warehouse_staff_assignments WHERE user_id=? AND company_id=? AND warehouse_id=? FOR UPDATE",staff,company,warehouse);
        Boolean old=previous.isEmpty()?null:(Boolean)previous.getFirst().get("active");
        if(Objects.equals(old,active) || (old==null && !active)) return assignments(warehouse);
        sql.update("INSERT INTO warehouse_staff_assignments(user_id,company_id,warehouse_id,active) VALUES(?,?,?,?) ON CONFLICT(user_id,warehouse_id) DO UPDATE SET active=EXCLUDED.active",staff,company,warehouse,active);
        // Existing audit_logs, no second assignment/history table or production schema mutation.
        sql.update("INSERT INTO audit_logs(user_id,company_id,action_type,entity_name,entity_id,old_values,new_values,created_at) VALUES(?,?,'WAREHOUSE_STAFF_ASSIGNMENT','warehouse_staff_assignments',?,jsonb_build_object('active',?::boolean),jsonb_build_object('userId',?::bigint,'warehouseId',?::bigint,'active',?::boolean),clock_timestamp())",
            actor.getId(),company,Long.toString(warehouse),old,staff,warehouse,active);
        return assignments(warehouse);
    }
    @Transactional(readOnly=true)
    public List<Change> history(long warehouse,int page) {
        if(page<0 || page>100000) throw new AppException(ErrorCode.INVALID_INPUT);
        long company=manager(false).getCompany().getId();warehouse(company,warehouse,false,false);
        return sql.query("SELECT a.id,a.user_id,u.full_name,(a.new_values->>'userId')::bigint AS staff_id,(a.old_values->>'active')::boolean AS old_active,(a.new_values->>'active')::boolean AS new_active,a.created_at FROM audit_logs a LEFT JOIN users u ON u.id=a.user_id AND u.company_id=a.company_id WHERE a.company_id=? AND a.action_type='WAREHOUSE_STAFF_ASSIGNMENT' AND a.entity_name='warehouse_staff_assignments' AND a.entity_id=? ORDER BY a.id DESC LIMIT 50 OFFSET ?",
            (r,n)->new Change(r.getLong("id"),r.getLong("user_id"),r.getString("full_name"),r.getLong("staff_id"),(Boolean)r.getObject("old_active"),r.getBoolean("new_active"),r.getTimestamp("created_at").toInstant()),company,Long.toString(warehouse),(long)page*50);
    }
}
