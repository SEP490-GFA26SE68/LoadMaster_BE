package fu.se184491.loadmaster_be.service.vehicle;

import fu.se184491.loadmaster_be.entity.vehicle.Vehicle;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.exception.*;
import fu.se184491.loadmaster_be.repository.vehicle.VehicleRepository;
import fu.se184491.loadmaster_be.repository.trip.TripRepository;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.dto.response.vehicle.VehicleResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.*;
import java.util.*;

@Service @RequiredArgsConstructor
public class VehicleDepartureEligibility {
    private final VehiclePresenceStore store;
    private final VehicleRepository vehicles;
    private final TripRepository trips;
    private final CurrentUserService current;
    public boolean eligible(Vehicle vehicle,Long warehouse,Long trip) {
        if(vehicle==null || warehouse==null || vehicle.getCompany()==null) return false;
        var p=store.presence(vehicle.getId(),vehicle.getCompany().getId());
        return vehicle.getStatus()==fu.se184491.loadmaster_be.constant.vehicle.VehicleStatus.AVAILABLE
            && "AT_WAREHOUSE".equals(p.status()) && Objects.equals(warehouse,p.warehouseId())
            && !store.busy(vehicle.getId(),trip);
    }
    /** Must run within the caller's transaction. The vehicle row serializes checks and allocation. */
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Vehicle requireLocked(long company,Long warehouse,long vehicle,Long trip) {
        if(warehouse==null || !store.warehouse(warehouse,company)) throw new AppException(ErrorCode.INVALID_INPUT);
        var locked=store.lockVehicle(vehicle,company);
        if(!store.lockWarehouse(warehouse,company)) throw new AppException(ErrorCode.INVALID_INPUT);
        var p=store.presence(vehicle,company);
        if(!"AVAILABLE".equals(locked.get("status")) || !"AT_WAREHOUSE".equals(p.status())
            || !Objects.equals(warehouse,p.warehouseId()) || store.busy(vehicle,trip))
            throw new AppException(ErrorCode.VEHICLE_DEPARTURE_INELIGIBLE);
        return vehicles.findByIdAndCompanyId(vehicle,company).orElseThrow(()->new AppException(ErrorCode.VEHICLE_NOT_FOUND));
    }
    @Transactional(readOnly=true)
    public Page<VehicleResponse> choices(long warehouse,Long tripId,int page,int size) {
        long company=current.getCurrentCompanyId();
        if(page<0 || size<1 || size>100) throw new AppException(ErrorCode.INVALID_INPUT);
        if(!store.warehouse(warehouse,company)) throw new AppException(ErrorCode.INVALID_INPUT);
        if(tripId!=null) {
            Trip trip=trips.findByIdAndCompanyId(tripId,company).orElseThrow(()->new AppException(ErrorCode.TRIP_NOT_FOUND));
            if(trip.getStatus()!=fu.se184491.loadmaster_be.constant.trip.TripStatus.DRAFT && trip.getStatus()!=fu.se184491.loadmaster_be.constant.trip.TripStatus.PLANNED) throw new AppException(ErrorCode.TRIP_NOT_EDITABLE);
        }
        var data=store.eligibleIds(company,warehouse,tripId,page,size).stream().map(id->vehicles.findByIdAndCompanyId(id,company).orElseThrow(()->new AppException(ErrorCode.VEHICLE_NOT_FOUND))).map(v->VehicleResponse.builder().id(v.getId()).name(v.getName()).licensePlate(v.getLicensePlate()).companyId(company).companyName(v.getCompany().getCompanyName()).vehicleTypeId(v.getVehicleType().getId()).vehicleTypeName(v.getVehicleType().getName()).status(v.getStatus()).build()).toList();
        return new PageImpl<>(data,PageRequest.of(page,size),store.eligibleCount(company,warehouse,tripId));
    }
}
