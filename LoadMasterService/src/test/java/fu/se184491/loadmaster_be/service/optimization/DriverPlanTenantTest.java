package fu.se184491.loadmaster_be.service.optimization;
import fu.se184491.loadmaster_be.service.driver.Impl.DriverServiceImpl;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.repository.trip.*;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
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
 @InjectMocks DriverServiceImpl service;
 @Test void listFiltersLegacyCrossCompanyDriverAssignment(){
  when(current.getCurrentCompanyId()).thenReturn(1L);when(users.findById(11L)).thenReturn(Optional.of(User.builder().id(11L).build()));
  when(trips.findByDriver_IdOrderByDepartureTimeDesc(11L)).thenReturn(List.of(
   Trip.builder().id(7L).company(Company.builder().id(1L).build()).build(),
   Trip.builder().id(8L).company(Company.builder().id(2L).build()).build(),Trip.builder().id(9L).build()));
  var result=service.listTrips(11L);assertEquals(1,result.size());assertEquals(7L,result.getFirst().getTripId());
  verify(stops,never()).countByTripId(8L);verify(stops,never()).countByTripId(9L);
 }
}
