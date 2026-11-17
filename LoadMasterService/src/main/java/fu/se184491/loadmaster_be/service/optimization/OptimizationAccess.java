package fu.se184491.loadmaster_be.service.optimization;

import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.entity.optimization.LoadPlan;
import fu.se184491.loadmaster_be.exception.*;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.optimization.*;
import fu.se184491.loadmaster_be.repository.trip.TripRepository;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Tenant authorization for user-facing optimization data. Service credentials confer no tenant bypass. */
@Component("optimizationAccess")
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OptimizationAccess {
    private final CurrentUserService currentUser;
    private final OptimizationJobRepository jobs;
    private final TripRepository trips;
    private final PackagePlacementRepository placements;
    private final UserRepository users;

    public boolean canReadJob(String uuid) {
        Long company = requireCompanyId(null);
        // Match Python's exact UUID lookup; a numeric primary key is not an alternate remote identifier.
        var job = jobs.findByJobUuid(uuid)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND));
        requireTripCompany(job.getTrip(), company);
        return true;
    }

    public boolean canReadTrip(Long id) {
        Long company = requireCompanyId(null);
        requireTripCompany(trips.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.TRIP_NOT_FOUND)), company);
        return true;
    }

    public boolean canReadPlacement(Long id) {
        Long company = requireCompanyId(null);
        var placement = placements.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.PLACEMENT_NOT_FOUND));
        LoadPlan plan = placement.getLoadPlan();
        requireTripCompany(plan == null || plan.getJob() == null ? null : plan.getJob().getTrip(), company);
        return true;
    }

    public boolean canReadUser(Long id) {
        if (id == null) throw new AppException(ErrorCode.USER_NOT_FOUND);
        Long company = requireCompanyId(null);
        var user = users.findById(id).orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
        if (user.getCompany() == null || !company.equals(user.getCompany().getId())) deny();
        return true;
    }

    public Long requireCompanyId(Long supplied) {
        Long company = currentUser.getCurrentCompanyId();
        if (company == null || supplied != null && !company.equals(supplied)) deny();
        return company;
    }

    private void requireTripCompany(Trip trip, Long company) {
        if (trip == null || trip.getCompany() == null || !company.equals(trip.getCompany().getId())) deny();
    }

    private void deny() { throw new AccessDeniedException("Resource does not belong to the current company"); }
}
