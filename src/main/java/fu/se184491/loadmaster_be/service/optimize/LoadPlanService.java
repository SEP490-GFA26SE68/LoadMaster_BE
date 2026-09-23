package fu.se184491.loadmaster_be.service.optimize;

import fu.se184491.loadmaster_be.dto.response.optimization.LoadPlanResponse;

import java.util.List;

public interface LoadPlanService {

    /**
     * Returns all LoadPlans (with placements) for a given job UUID.
     *
     * @param jobUuid UUID of the OptimizationJob
     * @return list of LoadPlanResponse ordered by id asc
     * @throws fu.se184491.loadmaster_be.exception.AppException OPTIMIZATION_JOB_NOT_FOUND
     */
    List<LoadPlanResponse> getPlansForJob(String jobUuid);

    /**
     * Dispatcher approves a load plan (S3-11).
     *
     * <ol>
     *   <li>Verify plan exists and has at least one placement.</li>
     *   <li>Verify LIFO ordering is respected (stepSequence is monotone).</li>
     *   <li>Set {@code approved = true} and {@code approvedBy = currentUser}.</li>
     *   <li>INSERT AuditLog with action {@code PLAN_APPROVED}.</li>
     * </ol>
     *
     * @param planId        id of the LoadPlan to approve
     * @param approverEmail the email of the dispatcher from the JWT principal
     * @return the updated {@link LoadPlanResponse}
     * @throws fu.se184491.loadmaster_be.exception.AppException LOAD_PLAN_NOT_FOUND if plan missing
     * @throws fu.se184491.loadmaster_be.exception.AppException PLAN_HAS_NO_PLACEMENTS if 0 placements
     * @throws fu.se184491.loadmaster_be.exception.AppException PLAN_LIFO_INVALID if LIFO broken
     */
    LoadPlanResponse approvePlan(Long planId, String approverEmail);
}
