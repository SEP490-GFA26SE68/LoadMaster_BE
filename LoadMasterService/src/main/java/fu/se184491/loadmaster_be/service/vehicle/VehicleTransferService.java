package fu.se184491.loadmaster_be.service.vehicle;

import fu.se184491.loadmaster_be.constant.account.*;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.exception.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import java.util.*;

@Service @RequiredArgsConstructor
public class VehicleTransferService {
    private final VehicleTransferStore transfers;
    private final VehiclePresenceStore presence;
    private final CurrentUserService current;
    private User actor(UserRole role) {
        User u=current.getCurrentUser();
        if(u.getCompany()==null || u.getStatus()!=UserStatus.ACTIVE || u.getUserRoleType()!=role) throw new AppException(ErrorCode.UNAUTHORIZED);
        return u;
    }
    private long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private void require(boolean condition){if(!condition)throw new AppException(ErrorCode.VEHICLE_TRANSFER_CONFLICT);}
    private void scope(User u,long warehouse,boolean lock) {
        boolean allowed=lock?presence.lockAssignment(u.getId(),u.getCompany().getId(),warehouse):presence.assigned(u.getId(),u.getCompany().getId(),warehouse)&&presence.warehouse(warehouse,u.getCompany().getId());
        if(!allowed)throw new AppException(ErrorCode.VEHICLE_WAREHOUSE_FORBIDDEN);
    }
    @Transactional(readOnly=true)
    public List<Map<String,Object>> warehouses(){return transfers.warehouses(actor(UserRole.DISPATCHER).getCompany().getId());}
    @Transactional(readOnly=true)
    public Page<Map<String,Object>> candidates(long warehouse,int page){var u=actor(UserRole.DISPATCHER);if(page<0||page>100000)throw new AppException(ErrorCode.INVALID_INPUT);if(!presence.warehouse(warehouse,u.getCompany().getId()))throw new AppException(ErrorCode.VEHICLE_WAREHOUSE_FORBIDDEN);return transfers.candidates(u.getCompany().getId(),warehouse,page);}
    @Transactional(readOnly=true)
    public Page<Map<String,Object>> list(Long warehouse,String status,int page) {
        if(page<0||page>100000||!Set.of("","REQUESTED","IN_TRANSIT","COMPLETED","CANCELLED").contains(status))throw new AppException(ErrorCode.INVALID_INPUT);
        var u=actor(warehouse==null?UserRole.DISPATCHER:UserRole.WAREHOUSE_WORKER);
        if(warehouse!=null)scope(u,warehouse,false);
        return transfers.list(u.getCompany().getId(),warehouse,status,page);
    }
    @Transactional
    public Map<String,Object> request(long vehicle,long source,long destination,String reason,UUID key) {
        var u=actor(UserRole.DISPATCHER);long company=u.getCompany().getId();
        if(source==destination||reason==null||reason.isBlank()||reason.length()>500)throw new AppException(ErrorCode.INVALID_INPUT);
        // Request key serializes cross-vehicle reuse; all vehicle-state mutations serialize on vehicle.
        presence.lockRequest(company,"transfer-request:"+key);
        var locked=presence.lockVehicle(vehicle,company);transfers.actor(u.getId(),company,"DISPATCHER");
        var old=transfers.byKey(company,key);
        if(old!=null){require(number(old,"vehicle_id")==vehicle&&number(old,"source_warehouse_id")==source&&number(old,"destination_warehouse_id")==destination&&number(old,"requested_by")==u.getId()&&Objects.equals(old.get("reason"),reason.trim()));return transfers.timestamps(old);}
        // Warehouse locks in ID order, independent of travel direction.
        if(!presence.lockWarehouse(Math.min(source,destination),company)||!presence.lockWarehouse(Math.max(source,destination),company))throw new AppException(ErrorCode.VEHICLE_WAREHOUSE_FORBIDDEN);
        var p=presence.presence(vehicle,company);
        require("AVAILABLE".equals(locked.get("status"))&&"AT_WAREHOUSE".equals(p.status())&&Objects.equals(p.warehouseId(),source)&&!presence.busy(vehicle,null));
        long id=transfers.create(company,vehicle,source,destination,u.getId(),key,reason.trim());
        transfers.audit(id,company,u.getId(),null,"REQUESTED");
        return transfers.timestamps(transfers.find(id,company,false));
    }
    @Transactional
    public Map<String,Object> cancel(long id) {
        var u=actor(UserRole.DISPATCHER);long company=u.getCompany().getId();
        var row=transfers.find(id,company,false);presence.lockVehicle(number(row,"vehicle_id"),company);
        row=transfers.find(id,company,true);transfers.actor(u.getId(),company,"DISPATCHER");
        if("CANCELLED".equals(row.get("status")))return transfers.timestamps(row);
        require("REQUESTED".equals(row.get("status")));
        transfers.transition(id,"CANCELLED",u.getId(),null);transfers.audit(id,company,u.getId(),"REQUESTED","CANCELLED");
        return transfers.timestamps(transfers.find(id,company,false));
    }
    @Transactional
    public Map<String,Object> confirm(long warehouse,long id,boolean arrival,UUID key) {
        var u=actor(UserRole.WAREHOUSE_WORKER);long company=u.getCompany().getId();
        presence.lockRequest(company,"transfer-"+(arrival?"arrival:":"departure:")+key);
        var row=transfers.find(id,company,false);
        long vehicle=number(row,"vehicle_id");
        var locked=presence.lockVehicle(vehicle,company);row=transfers.find(id,company,true);
        if(number(row,arrival?"destination_warehouse_id":"source_warehouse_id")!=warehouse)throw new AppException(ErrorCode.VEHICLE_WAREHOUSE_FORBIDDEN);
        scope(u,warehouse,true);
        String prefix=arrival?"arrival":"departure";
        require(transfers.actionKeyAvailable(company,id,arrival,key));
        if(row.get(prefix+"_key")!=null){require(Objects.equals(row.get(prefix+"_key"),key)&&number(row,arrival?"arrived_by":"departed_by")==u.getId());return transfers.timestamps(row);}
        require((arrival?"IN_TRANSIT":"REQUESTED").equals(row.get("status"))&&!presence.tripBusy(vehicle,null));
        if(!arrival)require("AVAILABLE".equals(locked.get("status")));
        var p=presence.presence(vehicle,company);
        require(arrival?"IN_TRANSIT".equals(p.status())&&p.warehouseId()==null:"AT_WAREHOUSE".equals(p.status())&&Objects.equals(p.warehouseId(),warehouse));
        presence.change(vehicle,company,warehouse,arrival?"AT_WAREHOUSE":"IN_TRANSIT",u.getId(),"transfer:"+id+":"+prefix,arrival?"CHECK_IN":"CHECK_OUT");
        transfers.transition(id,arrival?"COMPLETED":"IN_TRANSIT",u.getId(),key);
        transfers.audit(id,company,u.getId(),arrival?"IN_TRANSIT":"REQUESTED",arrival?"COMPLETED":"IN_TRANSIT");
        return transfers.timestamps(transfers.find(id,company,false));
    }
}
