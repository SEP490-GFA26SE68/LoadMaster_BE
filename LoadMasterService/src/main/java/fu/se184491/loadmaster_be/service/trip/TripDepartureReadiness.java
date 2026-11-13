package fu.se184491.loadmaster_be.service.trip;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.optimization.LoadingExecutionStatus;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.optimization.LoadPlan;
import fu.se184491.loadmaster_be.entity.optimization.LoadingExecution;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.optimization.LoadPlanRepository;
import fu.se184491.loadmaster_be.repository.trip.TripRepository;
import fu.se184491.loadmaster_be.repository.warehouse.LoadingExecutionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.Objects;

/** One readiness predicate for the Driver list, admission and the state transition. */
@Component
@RequiredArgsConstructor
public class TripDepartureReadiness {
    private final LoadPlanRepository plans;
    private final LoadingExecutionRepository executions;
    private final TripRepository trips;
    @PersistenceContext private EntityManager entityManager;

    /** Must run inside the caller's write transaction. Serialize departures by Trip first. */
    public Trip lockAssignedTrip(Long tripId, User driver) {
        if (driver == null || driver.getId() == null || driver.getUserRoleType() != UserRole.DRIVER
                || driver.getCompany() == null || driver.getCompany().getId() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
        Trip trip = trips.findByIdAndCompanyIdForUpdate(tripId, driver.getCompany().getId())
                .orElseThrow(() -> new AppException(ErrorCode.TRIP_NOT_FOUND));
        // A managed Trip may have been loaded before another request committed departure.
        entityManager.refresh(trip);
        if (trip.getDriver() == null || !Objects.equals(driver.getId(), trip.getDriver().getId())) {
            throw new AppException(ErrorCode.TRIP_NOT_ASSIGNED_TO_DRIVER);
        }
        return trip;
    }

    public boolean canStart(Trip trip, User driver) { return evaluate(trip, driver, false); }

    /** Caller holds the Trip write lock; lock/refresh the exact Plan then Execution, in that order. */
    public boolean canStartLocked(Trip trip, User driver) { return evaluate(trip, driver, true); }

    private boolean evaluate(Trip trip, User driver, boolean lock) {
        if (driver == null || driver.getId() == null || driver.getUserRoleType() != UserRole.DRIVER
                || driver.getCompany() == null || driver.getCompany().getId() == null
                || trip == null || trip.getId() == null || trip.getStatus() != TripStatus.LOADING
                || trip.getCompany() == null
                || !Objects.equals(driver.getCompany().getId(), trip.getCompany().getId())
                || trip.getDriver() == null || !Objects.equals(driver.getId(), trip.getDriver().getId())) {
            return false;
        }
        LoadPlan plan = plans.findFirstByJobTripIdAndApprovedTrueOrderByIdDesc(trip.getId()).orElse(null);
        if (plan == null) return false;
        if (lock) entityManager.refresh(plan, LockModeType.PESSIMISTIC_READ);
        if (plan.getId() == null || !Boolean.TRUE.equals(plan.getApproved()) || Boolean.TRUE.equals(plan.getStale())
                || plan.getJob() == null || plan.getJob().getTrip() == null
                || !Objects.equals(trip.getId(), plan.getJob().getTrip().getId())
                || plan.getJob().getTrip().getCompany() == null
                || !Objects.equals(trip.getCompany().getId(), plan.getJob().getTrip().getCompany().getId())) {
            return false;
        }
        LoadingExecution execution = executions.findByLoadPlanId(plan.getId()).orElse(null);
        if (execution == null) return false;
        if (lock) entityManager.refresh(execution, LockModeType.PESSIMISTIC_READ);
        return execution.getLoadPlan() != null && Objects.equals(plan.getId(), execution.getLoadPlan().getId())
                && execution.getStatus() == LoadingExecutionStatus.COMPLETED;
    }
}
