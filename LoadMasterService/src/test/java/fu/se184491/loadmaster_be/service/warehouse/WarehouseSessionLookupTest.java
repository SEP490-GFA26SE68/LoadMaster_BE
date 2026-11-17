package fu.se184491.loadmaster_be.service.warehouse;

import fu.se184491.loadmaster_be.constant.optimization.LoadingExecutionStatus;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.optimization.*;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.trip.TripRepository;
import fu.se184491.loadmaster_be.repository.optimization.*;
import fu.se184491.loadmaster_be.repository.warehouse.*;
import fu.se184491.loadmaster_be.repository.common.AuditLogRepository;
import fu.se184491.loadmaster_be.service.trip.TripPackageResolver;
import fu.se184491.loadmaster_be.exception.AppException;
import jakarta.persistence.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WarehouseSessionLookupTest {
    @Mock CurrentUserService principal; @Mock TripRepository trips;
    @Mock LoadPlanRepository plans; @Mock LoadingExecutionRepository executions;
    @Mock PackagePlacementRepository placements; @Mock PlacementConfirmationRepository confirmations;
    @Mock DeviationRepository deviations; @Mock TripPackageResolver packages;
    @Mock WarehouseProgress progress; @Mock AuditLogRepository audit; @Mock EntityManager em;
    WarehouseSessionLookup lookup;
    Trip trip; LoadPlan plan; LoadingExecution execution; PackagePlacement placement;
    @BeforeEach void setUp() {
        var company=Company.builder().id(1L).build();
        trip=Trip.builder().id(10L).company(company).status(TripStatus.LOADING).build();
        plan=LoadPlan.builder().id(50L).approved(true).stale(false).job(OptimizationJob.builder().trip(trip).build()).build();
        execution=LoadingExecution.builder().id(60L).loadPlan(plan).status(LoadingExecutionStatus.IN_PROGRESS).build();
        placement=PackagePlacement.builder().id(71L).loadPlan(plan).cargoPackage(CargoPackage.builder().id(1L).company(company).build()).build();
        lookup=new WarehouseSessionLookup(principal,trips,plans,executions,placements,confirmations,deviations,packages,progress,audit);
        org.springframework.test.util.ReflectionTestUtils.setField(lookup,"entityManager",em);
        lenient().when(principal.getCurrentUser()).thenReturn(User.builder().id(9L).company(company).build());
        lenient().when(trips.findByIdAndCompanyIdForUpdate(10L,1L)).thenReturn(Optional.of(trip));
        lenient().when(plans.findFirstByJobTripIdAndApprovedTrueOrderByIdDesc(10L)).thenReturn(Optional.of(plan));
        lenient().when(executions.findByLoadPlanId(50L)).thenReturn(Optional.of(execution));
        lenient().when(placements.findByLoadPlanIdOrderByStepSequenceAsc(50L)).thenReturn(List.of(placement));
        lenient().when(placements.findById(71L)).thenReturn(Optional.of(placement));
    }
    @Test void locksTripThenPlanThenExecutionBeforeActiveSession() {
        assertEquals(placement,lookup.placement(lookup.activeSessionOfPlacement(71L,10L),71L));
        var order=inOrder(trips,em,plans,executions);
        order.verify(trips).findByIdAndCompanyIdForUpdate(10L,1L);
        order.verify(em).refresh(trip);
        order.verify(plans).findFirstByJobTripIdAndApprovedTrueOrderByIdDesc(10L);
        order.verify(em).refresh(plan,LockModeType.PESSIMISTIC_READ);
        order.verify(executions).findByLoadPlanId(50L);
        order.verify(em).refresh(execution,LockModeType.PESSIMISTIC_WRITE);
    }
    @Test void oldApprovedPlacementRejected() {
        placement.setLoadPlan(LoadPlan.builder().id(49L).job(plan.getJob()).build());
        assertThrows(AppException.class,()->lookup.activeSessionOfPlacement(71L,10L));
    }
    @Test void staleCurrentPlanRejected(){plan.setStale(true);assertThrows(AppException.class,()->lookup.activeSession(10L));}
    @Test void wrongTripContextRejectedBeforeLock(){assertThrows(AppException.class,()->lookup.activeSessionOfPlacement(71L,11L));verifyNoInteractions(trips);}
    @Test void otherCompanyRejected(){when(trips.findByIdAndCompanyIdForUpdate(10L,1L)).thenReturn(Optional.empty());assertThrows(AppException.class,()->lookup.activeSessionOfPlacement(71L,10L));}
    @Test void closedExecutionsRejected(){for(var status:List.of(LoadingExecutionStatus.COMPLETED,LoadingExecutionStatus.ABORTED)){execution.setStatus(status);assertThrows(AppException.class,()->lookup.activeSession(10L));}}
    @Test void noPlanRejected(){when(plans.findFirstByJobTripIdAndApprovedTrueOrderByIdDesc(10L)).thenReturn(Optional.empty());assertThrows(AppException.class,()->lookup.activeSession(10L));}
    @Test void noExecutionRejected(){when(executions.findByLoadPlanId(50L)).thenReturn(Optional.empty());assertThrows(AppException.class,()->lookup.activeSession(10L));}
    @Test void tripMustBeLoading(){trip.setStatus(TripStatus.PLANNED);assertThrows(AppException.class,()->lookup.activeSession(10L));}
    @Test void packageCompanyMustMatchSession(){placement.getCargoPackage().setCompany(Company.builder().id(2L).build());var session=lookup.activeSession(10L);assertThrows(AppException.class,()->lookup.placement(session,71L));assertThrows(AppException.class,()->lookup.placementOfPackage(session,1L));}
    @Test void executionCannotBelongToAnotherPlan(){execution.setLoadPlan(LoadPlan.builder().id(49L).build());assertThrows(AppException.class,()->lookup.activeSession(10L));}
}
