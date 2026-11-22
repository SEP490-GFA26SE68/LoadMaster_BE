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
    /** Ordinary check-in. The vehicle of a LOADING trip that leaves from this warehouse goes through the same
     * physical verification as {@link #confirmLoadingPresence}, so the "vehicle at warehouse" screen also unblocks
     * its departure; any other active trip (LOADING elsewhere, IN_TRANSIT) still makes the vehicle busy. */
    @Transactional
    public VehiclePresenceStore.Presence checkIn(long warehouse,long vehicle,UUID key) {
        var u=worker();scope(u,warehouse);long company=u.getCompany().getId();
        // Read without a lock: confirmLoadingPresence locks Trip before Vehicle and rechecks everything.
        Long loadingTrip=store.loadingTripFrom(vehicle,company,warehouse);
        if(loadingTrip!=null) return confirmLoadingPresence(warehouse,loadingTrip,vehicle,key);
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
    /** Explicit physical verification of the loading trip's own vehicle at the trip's origin: the worker sees it there.
     * Presence UNKNOWN or IN_TRANSIT (nobody checked it in after an earlier trip) becomes AT_WAREHOUSE at the origin;
     * AT_WAREHOUSE at another warehouse is first checked out of it. Already at the origin is a no-op (receipt only).
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
        if(store.activeTransfer(vehicle)) throw new AppException(ErrorCode.VEHICLE_TRANSFER_CONFLICT);
        if(!"AVAILABLE".equals(locked.get("status")) || store.tripBusy(vehicle,trip)) throw new AppException(ErrorCode.VEHICLE_BUSY);
        var old=store.receipt(company,key.toString());
        if(old!=null) {
            if(((Number)old.get("vehicle_id")).longValue()!=vehicle || ((Number)old.get("warehouse_id")).longValue()!=warehouse
                || ((Number)old.get("actor_user_id")).longValue()!=u.getId())
                throw new AppException(ErrorCode.VEHICLE_PRESENCE_CONFLICT);
            var current=store.presence(vehicle,company);
            // A no-op confirmation leaves no event; its replay is still valid while the vehicle is at the origin.
            if(store.event(company,reference).isEmpty() && !atWarehouse(current,warehouse)) throw new AppException(ErrorCode.VEHICLE_PRESENCE_CONFLICT);
            return current;
        }
        var p=store.presence(vehicle,company);
        if(!atWarehouse(p,warehouse)) {
            // CHECK_IN only follows UNKNOWN / IN_TRANSIT: leave the other warehouse first, in the same transaction.
            if("AT_WAREHOUSE".equals(p.status())) store.change(vehicle,company,p.warehouseId(),"IN_TRANSIT",u.getId(),reference+":out","CHECK_OUT");
            store.change(vehicle,company,warehouse,"AT_WAREHOUSE",u.getId(),reference,"CHECK_IN");
        }
        store.receipt(company,key.toString(),vehicle,warehouse,u.getId());
        return store.presence(vehicle,company);
    }
    private static boolean atWarehouse(VehiclePresenceStore.Presence p,long warehouse) {
        return "AT_WAREHOUSE".equals(p.status()) && Objects.equals(p.warehouseId(),warehouse);
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
        if(!"AVAILABLE".equals(locked.get("status")) || trip.getOriginWarehouse()==null || store.busy(vehicle,trip.getId())) throw new AppException(ErrorCode.DRIVER_VEHICLE_DEPARTURE_BLOCKED);
        // In transit or at another warehouse: like UNKNOWN, a worker at the origin can confirm the vehicle (confirmLoadingPresence).
        if(!atWarehouse(p,trip.getOriginWarehouse().getId())) throw new AppException(ErrorCode.DRIVER_VEHICLE_PRESENCE_UNVERIFIED);
        store.change(vehicle,company,p.warehouseId(),"IN_TRANSIT",actor,"trip:"+trip.getId()+":departure","CHECK_OUT");
    }
}
