package fu.se184491.loadmaster_be.service.optimization;
import fu.se184491.loadmaster_be.service.driver.Impl.DriverServiceImpl;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.repository.trip.*;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import fu.se184491.loadmaster_be.service.trip.TripDepartureReadiness;
import fu.se184491.loadmaster_be.exception.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
@ExtendWith(MockitoExtension.class)
class DriverPlanTenantTest {
 @Mock TripRepository trips;
 @Mock DeliveryStopRepository stops;
 @Mock UserRepository users;
 @Mock CurrentUserService current;
 @Mock TripDepartureReadiness readiness;
 @InjectMocks DriverServiceImpl service;
 @Test void listUsesTrustedPrincipalAndBothDriverCompanyPredicates(){
  User driver=User.builder().id(11L).company(Company.builder().id(1L).build()).build();
  Trip own=Trip.builder().id(7L).company(driver.getCompany()).driver(driver).build();
  when(current.getCurrentUser()).thenReturn(driver);
  when(trips.findByDriver_IdAndCompany_IdOrderByDepartureTimeDesc(11L,1L)).thenReturn(List.of(own));
  var result=service.listTrips(11L);
  assertEquals(1,result.size());assertEquals(7L,result.getFirst().getTripId());
  verify(trips).findByDriver_IdAndCompany_IdOrderByDepartureTimeDesc(11L,1L);
  verify(trips,never()).findByDriver_IdOrderByDepartureTimeDesc(anyLong());
  verifyNoInteractions(users);
  verify(stops,never()).countByTripId(8L);verify(stops,never()).countByTripId(9L);
 }
 @Test void requestedDifferentDriverCannotOverridePrincipal(){
  when(current.getCurrentUser()).thenReturn(User.builder().id(11L).company(Company.builder().id(1L).build()).build());
  AppException error=assertThrows(AppException.class,()->service.listTrips(12L));
  assertEquals(ErrorCode.UNAUTHORIZED,error.getErrorCode());verifyNoInteractions(trips,stops,users);
 }
}
