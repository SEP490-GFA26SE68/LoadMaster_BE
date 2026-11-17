package fu.se184491.loadmaster_be.service.optimization;

import fu.se184491.loadmaster_be.constant.billing.SubscriptionTier;
import fu.se184491.loadmaster_be.constant.optimization.*;
import fu.se184491.loadmaster_be.entity.optimization.OptimizationJob;
import fu.se184491.loadmaster_be.exception.*;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.company.CompanyRepository;
import fu.se184491.loadmaster_be.repository.trip.TripRepository;
import fu.se184491.loadmaster_be.repository.optimization.*;
import fu.se184491.loadmaster_be.service.credit.CreditService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
@RequiredArgsConstructor
public class OptimizationAdmissionService {
    private final CurrentUserService currentUser;
    private final CompanyRepository companies;
    private final TripRepository trips;
    private final LoadPlanRepository plans;
    private final OptimizationJobRepository jobs;
    private final OptimizationEntitlementService entitlements;
    private final CreditService credits;

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    @org.springframework.beans.factory.annotation.Value("${app.optimization.lifecycle-v2.enabled:false}")
    private boolean lifecycleV2;
    @org.springframework.beans.factory.annotation.Value("${app.optimization.lifecycle-v2.dispatch-deadline-seconds:120}")
    private long dispatchDeadlineSeconds;

    @Transactional
    public OptimizationJob reserve(Long tripId, Long parentPlanId, OptimizationObjective objective,
                                   int seconds, Integer seed, String key) {
        if (key == null || key.isBlank() || key.length() > 128 || seconds < 10 || seconds > 600) {
            throw new AppException(ErrorCode.VALIDATION_ERROR);
        }
        Long companyId = currentUser.getCurrentCompanyId();
        if (companyId == null) throw new AccessDeniedException("Company is required");
        var company = companies.findByIdForUpdate(companyId)
                .orElseThrow(() -> new AppException(ErrorCode.COMPANY_NOT_FOUND));
        // A user lookup may already have loaded Company into the first-level cache before this lock.
        // Refresh under the held row lock so a concurrent suspension cannot leave a stale ACTIVE value.
        entityManager.refresh(company);
        if (company.getStatus() != fu.se184491.loadmaster_be.constant.company.CompanyStatus.ACTIVE) {
            throw new AccessDeniedException("Only an ACTIVE company may reserve an optimization action");
        }
        var trip = trips.findById(tripId)
                .orElseThrow(() -> new AppException(ErrorCode.TRIP_NOT_FOUND));
        if (trip.getCompany() == null || !companyId.equals(trip.getCompany().getId())) {
            throw new AccessDeniedException("Trip belongs to another company");
        }
        if (parentPlanId != null) {
            var parent = plans.findById(parentPlanId)
                    .orElseThrow(() -> new AppException(ErrorCode.LOAD_PLAN_NOT_FOUND));
            if (parent.getJob() == null || parent.getJob().getTrip() == null
                    || !tripId.equals(parent.getJob().getTrip().getId())
                    || parent.getJob().getTrip().getCompany() == null
                    || !companyId.equals(parent.getJob().getTrip().getCompany().getId())) {
                throw new AccessDeniedException("Parent plan belongs to another trip/company");
            }
        }
        // Company-scoped key identifies the ACTION, not the button or trip. New key = new action.
        String uuid = UUID.nameUUIDFromBytes(("loadmaster-optimization-v1:" + companyId + ":" + key)
                .getBytes(StandardCharsets.UTF_8)).toString();
        var existing = jobs.findByJobUuid(uuid);
        if (existing.isPresent()) {
            var j = existing.get();
            if (j.getTrip() == null || !tripId.equals(j.getTrip().getId())
                    || !companyId.toString().equals(j.getCompanyId())
                    || !Objects.equals(parentPlanId, j.getParentPlanId()) || j.getObjective() != objective
                    || !Objects.equals(seconds, j.getTimeLimitSec()) || !Objects.equals(seed, j.getSeed())) {
                throw new AppException(ErrorCode.OPTIMIZATION_IDEMPOTENCY_CONFLICT);
            }
            credits.deductCredit(companyId, uuid); // verifies committed USAGE under lock; cannot charge twice.
            return j;
        }
        var sub = entitlements.requireActive(companyId);
        SubscriptionTier tier = sub.getPlan().getTier();
        var job = OptimizationJob.builder().trip(trip).jobUuid(uuid).parentPlanId(parentPlanId)
                .companyId(companyId.toString()).subscriptionTier(tier).algorithmTier(tier.name())
                .algorithmName(OptimizationAlgorithm.valueOf(sub.getPlan().getAlgorithmTier().name()))
                .objective(objective).timeLimitSec(seconds).seed(seed).status(OptimizationJobStatus.PENDING).build();
        if (lifecycleV2) {
            if (dispatchDeadlineSeconds <= 0) throw new IllegalStateException("Invalid dispatch deadline");
            job.setExecutionGeneration(0L);
            java.time.Instant databaseNow = (java.time.Instant) entityManager
                    .createNativeQuery("select clock_timestamp()", java.time.Instant.class).getSingleResult();
            job.setDispatchExpiresAt(databaseNow.plusSeconds(dispatchDeadlineSeconds));
        }
        jobs.saveAndFlush(job);
        credits.deductCredit(companyId, uuid);
        // Job + account + USAGE commit together, before any network/solver call.
        return job;
    }
}
