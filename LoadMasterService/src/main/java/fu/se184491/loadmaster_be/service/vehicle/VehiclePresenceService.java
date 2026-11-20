package fu.se184491.loadmaster_be.service.vehicle;

import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.exception.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service @RequiredArgsConstructor
public class VehiclePresenceService {
    private final VehiclePresenceStore store;
    private final CurrentUserService current;
    private User worker() {
        User u=current.getCurrentUser();
        if(u.getCompany()==null || u.getUserRoleType()!=fu.se184491.loadmaster_be.constant.account.UserRole.WAREHOUSE_WORKER
            || u.getStatus()!=fu.se184491.loadmaster_be.constant.account.UserStatus.ACTIVE) throw new AppException(ErrorCode.UNAUTHORIZED);
        return u;
    }
    private void scope(User u,long warehouse) {
        if(!store.warehouse(warehouse,u.getCompany().getId()) || !store.assigned(u.getId(),u.getCompany().getId(),warehouse)) throw new AppException(ErrorCode.VEHICLE_WAREHOUSE_FORBIDDEN);
    }
    @Transactional(readOnly=true)
    public List<Map<String,Object>> warehouses() {var u=worker();return store.assignedWarehouses(u.getId(),u.getCompany().getId());}
    @Transactional(readOnly=true)
    public List<Map<String,Object>> search(long warehouse,String query) {
        var u=worker();scope(u,warehouse);
        if(query==null || query.trim().length()<2 || query.length()>80) throw new AppException(ErrorCode.INVALID_INPUT);
        return store.search(u.getCompany().getId(),query.trim());
    }
    @Transactional(readOnly=true)
    public org.springframework.data.domain.Page<Map<String,Object>> vehicles(long warehouse,String query,int page,int size) {return vehicles(warehouse,query,page,size,false);}
    @Transactional(readOnly=true)
    public org.springframework.data.domain.Page<Map<String,Object>> vehicles(long warehouse,String query,int page,int size,boolean atWarehouse) {
        var u=worker();scope(u,warehouse);
        String search=query==null?"":query.trim();
        if(page<0 || page>100000 || size<1 || size>100 || search.length()>80 || search.length()==1) throw new AppException(ErrorCode.INVALID_INPUT);
        return store.catalog(u.getCompany().getId(),warehouse,search,page,size,atWarehouse);
    }
    @Transactional
    public VehiclePresenceStore.Presence checkIn(long warehouse,long vehicle,UUID key) {
        var u=worker();scope(u,warehouse);long company=u.getCompany().getId();
        store.lockRequest(company,key.toString());
        store.lockVehicle(vehicle,company);
        // Recheck assignment after acquiring serialization lock; assignment revocation obtains its own row lock.
        if(!store.lockAssignment(u.getId(),company,warehouse)) throw new AppException(ErrorCode.VEHICLE_WAREHOUSE_FORBIDDEN);
        if(store.activeTransfer(vehicle)) throw new AppException(ErrorCode.VEHICLE_TRANSFER_CONFLICT);
        var old=store.receipt(company,key.toString());
        if(old!=null) {
            if(((Number)old.get("vehicle_id")).longValue()!=vehicle || ((Number)old.get("warehouse_id")).longValue()!=warehouse || ((Number)old.get("actor_user_id")).longValue()!=u.getId()) throw new AppException(ErrorCode.VEHICLE_PRESENCE_CONFLICT);
            return store.presence(vehicle,company);
        }
        if(store.busy(vehicle,null)) throw new AppException(ErrorCode.VEHICLE_BUSY);
        var p=store.presence(vehicle,company);
        if("AT_WAREHOUSE".equals(p.status())) {
            if(!Objects.equals(p.warehouseId(),warehouse)) throw new AppException(ErrorCode.VEHICLE_PRESENCE_CONFLICT);
            store.receipt(company,key.toString(),vehicle,warehouse,u.getId());
            return p;
        }
        store.change(vehicle,company,warehouse,"AT_WAREHOUSE",u.getId(),key.toString(),"CHECK_IN");
        store.receipt(company,key.toString(),vehicle,warehouse,u.getId());
        return store.presence(vehicle,company);
    }
    /** Explicit physical verification for a loading vehicle, including legacy UNKNOWN presence.
     * Trip -> request -> Vehicle -> assignment/warehouse. Never infer arrival from loading completion. */
    @Transactional
    public VehiclePresenceStore.Presence confirmLoadingPresence(long warehouse,long trip,long expectedVehicle,UUID key) {
        var u=worker();scope(u,warehouse);long company=u.getCompany().getId();
        var loading=store.lockTrip(trip,company);
        if(!"LOADING".equals(loading.get("status")) || !(loading.get("origin_warehouse_id") instanceof Number origin)
            || origin.longValue()!=warehouse || !(loading.get("vehicle_id") instanceof Number))
            throw new AppException(ErrorCode.VEHICLE_PRESENCE_CONFLICT);
        long vehicle=((Number)loading.get("vehicle_id")).longValue();
        if(vehicle!=expectedVehicle) throw new AppException(ErrorCode.VEHICLE_PRESENCE_CONFLICT);
        String reference="loading-trip:"+trip+":"+key;
        store.lockRequest(company,key.toString());
        var locked=store.lockVehicle(vehicle,company);
        if(!store.lockAssignment(u.getId(),company,warehouse)) throw new AppException(ErrorCode.VEHICLE_WAREHOUSE_FORBIDDEN);
        if(!"AVAILABLE".equals(locked.get("status")) || store.busy(vehicle,trip)) throw new AppException(ErrorCode.VEHICLE_BUSY);
        if(store.activeTransfer(vehicle)) throw new AppException(ErrorCode.VEHICLE_TRANSFER_CONFLICT);
        var old=store.receipt(company,key.toString());
        if(old!=null) {
            if(((Number)old.get("vehicle_id")).longValue()!=vehicle || ((Number)old.get("warehouse_id")).longValue()!=warehouse
                || ((Number)old.get("actor_user_id")).longValue()!=u.getId() || store.event(company,reference).isEmpty())
                throw new AppException(ErrorCode.VEHICLE_PRESENCE_CONFLICT);
            return store.presence(vehicle,company);
        }
        if(!"UNKNOWN".equals(store.presence(vehicle,company).status())) throw new AppException(ErrorCode.VEHICLE_PRESENCE_CONFLICT);
        store.change(vehicle,company,warehouse,"AT_WAREHOUSE",u.getId(),reference,"CHECK_IN");
        store.receipt(company,key.toString(),vehicle,warehouse,u.getId());
        return store.presence(vehicle,company);
    }
    @Transactional(readOnly=true)
    public List<VehiclePresenceStore.Event> history(long warehouse,long vehicle) {
        var u=worker();scope(u,warehouse);
        // Never use a client-supplied company, and do not reveal whether a foreign vehicle exists.
        if(!store.owned(u.getCompany().getId(),vehicle)) throw new AppException(ErrorCode.VEHICLE_NOT_FOUND);
        return store.events(vehicle,u.getCompany().getId()).stream().filter(event -> Objects.equals(event.warehouseId(),warehouse)).toList();
    }
    /** The authenticated Driver start transition is the departure event, not loading completion. */
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void departure(Trip trip,long actor) {
        long vehicle=trip.getVehicle().getId(),company=trip.getCompany().getId();
        var locked=store.lockVehicle(vehicle,company);
        var p=store.presence(vehicle,company);
        if("UNKNOWN".equals(p.status())) throw new AppException(ErrorCode.DRIVER_VEHICLE_PRESENCE_UNVERIFIED);
        if(!"AVAILABLE".equals(locked.get("status")) || !"AT_WAREHOUSE".equals(p.status()) || trip.getOriginWarehouse()==null || !Objects.equals(p.warehouseId(),trip.getOriginWarehouse().getId()) || store.busy(vehicle,trip.getId())) throw new AppException(ErrorCode.DRIVER_VEHICLE_DEPARTURE_BLOCKED);
        store.change(vehicle,company,p.warehouseId(),"IN_TRANSIT",actor,"trip:"+trip.getId()+":departure","CHECK_OUT");
    }
}
