package fu.se184491.loadmaster_be.service.trip;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.optimization.LoadingExecutionStatus;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.optimization.*;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.repository.optimization.LoadPlanRepository;
import fu.se184491.loadmaster_be.repository.trip.TripRepository;
import fu.se184491.loadmaster_be.repository.warehouse.LoadingExecutionRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TripDepartureReadinessTest {
    @Mock LoadPlanRepository plans;
    @Mock LoadingExecutionRepository executions;
    @Mock TripRepository trips;
    @Mock fu.se184491.loadmaster_be.service.vehicle.VehiclePresenceStore presenceStore;
    @Mock EntityManager entityManager;
    @InjectMocks TripDepartureReadiness readiness;
    User driver;
    Trip trip;
    LoadPlan current;

    @BeforeEach void fixture() {
        org.springframework.test.util.ReflectionTestUtils.setField(readiness, "entityManager", entityManager);
        Company company = Company.builder().id(1L).build();
        driver = User.builder().id(8L).company(company).userRoleType(UserRole.DRIVER).build();
        trip = Trip.builder().id(10L).vehicle(fu.se184491.loadmaster_be.entity.vehicle.Vehicle.builder().id(3L).build()).company(company).driver(driver).status(TripStatus.LOADING).build();
        current = LoadPlan.builder().id(50L).approved(true).stale(false)
                .job(OptimizationJob.builder().id(40L).trip(trip).build()).build();
        lenient().when(plans.findFirstByJobTripIdAndApprovedTrueOrderByIdDesc(10L)).thenReturn(Optional.of(current));
    }
    void execution(LoadPlan plan, LoadingExecutionStatus status) {
        when(executions.findByLoadPlanId(50L)).thenReturn(Optional.of(
                LoadingExecution.builder().id(60L).loadPlan(plan).status(status).build()));
    }
    @Test void lockingRefreshesAssignmentBeforeAuthorizing() {
        when(trips.findByIdAndCompanyIdForUpdate(10L, 1L)).thenReturn(Optional.of(trip));
        doAnswer(call -> { trip.setDriver(User.builder().id(9L).build()); return null; })
                .when(entityManager).refresh(trip);
        assertThrows(fu.se184491.loadmaster_be.exception.AppException.class,
                () -> readiness.lockAssignedTrip(10L, driver));
        verify(entityManager).refresh(trip);
    }
    @Test void lockingRefreshesPlanAndRejectsConcurrentInvalidation() {
        doAnswer(call -> { current.setStale(true); return null; })
                .when(entityManager).refresh(current, jakarta.persistence.LockModeType.PESSIMISTIC_READ);
        assertFalse(readiness.canStartLocked(trip, driver));
        verifyNoInteractions(executions);
    }
    @Test void currentApprovedCompletedCanStart() {
        execution(current, LoadingExecutionStatus.COMPLETED);
        assertTrue(readiness.canStart(trip, driver));
    }
    @Test void anOldCompletedPlanCannotAuthorizeTheNewPlan() {
        execution(current, LoadingExecutionStatus.IN_PROGRESS);
        lenient().when(executions.existsByLoadPlanJobTripIdAndStatus(10L, LoadingExecutionStatus.COMPLETED)).thenReturn(true);
        assertFalse(readiness.canStart(trip, driver));
        verify(executions, never()).existsByLoadPlanJobTripIdAndStatus(any(), any());
    }
    @Test void staleApprovedPlanBlocks() {
        current.setStale(true);
        assertFalse(readiness.canStart(trip, driver));
        verifyNoInteractions(executions);
    }
    @Test void noApprovedPlanBlocks() {
        when(plans.findFirstByJobTripIdAndApprovedTrueOrderByIdDesc(10L)).thenReturn(Optional.empty());
        assertFalse(readiness.canStart(trip, driver));
    }
    @Test void noExecutionBlocks() { assertFalse(readiness.canStart(trip, driver)); }
    @Test void anotherDriverBlocks() {
        driver.setId(9L); trip.setDriver(User.builder().id(8L).build());
        assertFalse(readiness.canStart(trip, driver));
        verifyNoInteractions(plans, executions);
    }
    @Test void anotherCompanyBlocks() {
        trip.setCompany(Company.builder().id(2L).build());
        assertFalse(readiness.canStart(trip, driver));
        verifyNoInteractions(plans, executions);
    }
    @Test void nonDriverRoleBlocks() {
        driver.setUserRoleType(UserRole.DISPATCHER);
        assertFalse(readiness.canStart(trip, driver));
    }
    @Test void planOfAnotherTripBlocks() {
        current.getJob().setTrip(Trip.builder().id(11L).company(trip.getCompany()).build());
        assertFalse(readiness.canStart(trip, driver));
        verifyNoInteractions(executions);
    }
    @Test void executionOfAnotherPlanBlocks() {
        execution(LoadPlan.builder().id(49L).build(), LoadingExecutionStatus.COMPLETED);
        assertFalse(readiness.canStart(trip, driver));
    }
    @Test void abortedExecutionBlocks() {
        execution(current, LoadingExecutionStatus.ABORTED);
        assertFalse(readiness.canStart(trip, driver));
    }
    @Test void alreadyInTransitBlocks() {
        trip.setStatus(TripStatus.IN_TRANSIT);
        assertFalse(readiness.canStart(trip, driver));
        verifyNoInteractions(plans, executions);
    }
    @Test void unapprovedPlanFailsClosedEvenIfRepositoryReturnsIt() {
        current.setApproved(false);
        assertFalse(readiness.canStart(trip, driver));
    }
}
