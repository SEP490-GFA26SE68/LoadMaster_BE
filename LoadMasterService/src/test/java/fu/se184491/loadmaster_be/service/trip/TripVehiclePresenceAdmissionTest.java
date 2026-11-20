package fu.se184491.loadmaster_be.service.trip;
import fu.se184491.loadmaster_be.service.vehicle.VehicleDepartureEligibility;
import fu.se184491.loadmaster_be.service.trip.impl.TripServiceImpl;
import fu.se184491.loadmaster_be.repository.trip.*;
import fu.se184491.loadmaster_be.repository.company.CompanyWarehouseRepository;
import fu.se184491.loadmaster_be.repository.vehicle.VehicleRepository;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import fu.se184491.loadmaster_be.repository.planning.DeliveryRequirementRepository;
import fu.se184491.loadmaster_be.repository.route.RoutePlanRepository;
import fu.se184491.loadmaster_be.repository.optimization.LoadPlanRepository;
import fu.se184491.loadmaster_be.repository.cargo.CargoPackageRepository;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.company.*;
import fu.se184491.loadmaster_be.entity.vehicle.Vehicle;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.dto.request.trip.*;
import fu.se184491.loadmaster_be.exception.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.*;
import java.math.BigDecimal;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness=org.mockito.quality.Strictness.LENIENT)
class TripVehiclePresenceAdmissionTest {
 @Mock TripRepository trips; @Mock VehicleDepartureEligibility eligibility;
 @Mock VehicleRepository vehicles; @Mock UserRepository users; @Mock CurrentUserService current;
 @Mock CompanyWarehouseRepository warehouses; @Mock CargoPackageRepository cargo;
 @Mock DeliveryStopRepository stops; @Mock DeliveryRequirementRepository requirements;
 @Mock RoutePlanRepository routes; @Mock TripStatusService statuses; @Mock LoadPlanRepository plans;
 @InjectMocks TripServiceImpl create;
 @InjectMocks TripLifecycleService edit;
 @BeforeEach void fixture(){
  var company=Company.builder().id(1L).build();when(current.getCurrentUser()).thenReturn(User.builder().id(7L).company(company).build());
  var warehouse=CompanyWarehouse.builder().id(101L).company(company).isActive(true).latitude(BigDecimal.ONE).longitude(BigDecimal.ONE).build();
  when(warehouses.findByIdAndCompanyId(101L,1L)).thenReturn(Optional.of(warehouse));
  when(trips.findByIdAndCompanyIdForUpdate(10L,1L)).thenReturn(Optional.of(Trip.builder().id(10L).company(company).originWarehouse(warehouse).vehicle(Vehicle.builder().id(1L).build()).status(TripStatus.DRAFT).build()));
 }
 @Test void createCallsTheSharedResolverWithPrincipalCompany(){when(eligibility.requireLocked(1,101L,3,null)).thenThrow(new AppException(ErrorCode.VEHICLE_DEPARTURE_INELIGIBLE));assertThrows(AppException.class,()->create.createTrip(new TripCreateRequest("Synthetic",java.time.LocalDateTime.now(),3L,null,101L)));verify(eligibility).requireLocked(1,101L,3,null);verify(trips,never()).save(any());}
 @Test void warehouseOnlyPatchRevalidatesExistingVehicle(){when(eligibility.requireLocked(1,102L,1,10L)).thenThrow(new AppException(ErrorCode.VEHICLE_DEPARTURE_INELIGIBLE));assertThrows(AppException.class,()->edit.updateTrip(10L,new TripUpdateRequest(null,null,null,null,null,102L)));verify(eligibility).requireLocked(1,102L,1,10L);verify(trips,never()).save(any());}
 @Test void unchangedVehicleStillCannotBypassEligibility(){when(eligibility.requireLocked(1,101L,1,10L)).thenThrow(new AppException(ErrorCode.VEHICLE_DEPARTURE_INELIGIBLE));assertThrows(AppException.class,()->edit.updateTrip(10L,new TripUpdateRequest(null,null,1L,null,null,null)));verify(eligibility).requireLocked(1,101L,1,10L);verify(trips,never()).save(any());}
}
