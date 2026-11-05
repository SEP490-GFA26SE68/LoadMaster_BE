package fu.se184491.loadmaster_be.service.optimization.Impl;

import fu.se184491.loadmaster_be.client.OptimizationClient;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.dto.response.optimization.LoadPlanDetailResponse;
import fu.se184491.loadmaster_be.dto.response.optimization.PlacementDetailResponse;
import fu.se184491.loadmaster_be.dto.response.optimization.TripLoadPlansResponse;
import fu.se184491.loadmaster_be.dto.response.optimization.UnplacedPackageResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.optimization.LoadPlanReadRepository;
import fu.se184491.loadmaster_be.repository.optimization.LoadPlanReadRepository.JobOwner;
import fu.se184491.loadmaster_be.repository.optimization.LoadPlanReadRepository.PlanWithCompany;
import fu.se184491.loadmaster_be.repository.trip.TripRepository;
import fu.se184491.loadmaster_be.service.optimization.LoadPlanQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class LoadPlanQueryServiceImpl implements LoadPlanQueryService {

    private final LoadPlanReadRepository readRepository;
    private final TripRepository tripRepository;
    private final CurrentUserService currentUserService;
    private final OptimizationClient optimizationClient;

    @Override
    public TripLoadPlansResponse getTripLoadPlans(Long tripId) {
        Long companyId = currentUserService.getCurrentCompanyId();
        tripRepository.findByIdAndCompanyId(tripId, companyId)
                .orElseThrow(() -> new AppException(ErrorCode.TRIP_NOT_FOUND));

        List<LoadPlanDetailResponse> plans = withItems(readRepository.findPlansByTrip(tripId));
        Long approvedPlanId = plans.stream()
                .filter(plan -> Boolean.TRUE.equals(plan.getApproved()))
                .map(LoadPlanDetailResponse::getId)
                .max(Comparator.naturalOrder())
                .orElse(null);

        return TripLoadPlansResponse.builder()
                .tripId(tripId)
                .approvedPlanId(approvedPlanId)
                .plans(plans)
                .build();
    }

    @Override
    public LoadPlanDetailResponse getPlan(Long planId) {
        return withItems(List.of(ownedPlan(planId))).get(0);
    }

    @Override
    public List<LoadPlanDetailResponse> getJobPlans(String jobUuid) {
        JobOwner owner = requireOwnedJob(jobUuid);
        return withItems(readRepository.findPlansByJob(owner.jobId()));
    }

    @Override
    public JobOwner requireOwnedJob(String jobUuid) {
        Long companyId = currentUserService.getCurrentCompanyId();
        return readRepository.findJobOwner(jobUuid)
                .filter(owner -> Objects.equals(owner.companyId(), companyId))
                .orElseThrow(() -> new AppException(ErrorCode.OPTIMIZATION_JOB_NOT_FOUND));
    }

    @Override
    public void requireOwnedPlan(Long planId) {
        ownedPlan(planId);
    }

    @Override
    public LoadPlanDetailResponse approvePlan(Long planId) {
        LoadPlanDetailResponse plan = ownedPlan(planId);
        if (Boolean.TRUE.equals(plan.getApproved())) {
            throw new AppException(ErrorCode.PLAN_ALREADY_APPROVED);
        }
        // Kho và tài xế đọc phương án đã duyệt: hết pha lập kế hoạch thì không đổi được phương án nữa.
        tripRepository.findById(plan.getTripId())
                .filter(trip -> trip.getStatus() == TripStatus.PLANNED)
                .orElseThrow(() -> new AppException(ErrorCode.PLAN_TRIP_NOT_PLANNED));
        User approver = currentUserService.getCurrentUser();
        optimizationClient.approvePlan(planId, approver.getId());
        return getPlan(planId);
    }

    private LoadPlanDetailResponse ownedPlan(Long planId) {
        Long companyId = currentUserService.getCurrentCompanyId();
        return readRepository.findPlan(planId)
                .filter(found -> Objects.equals(found.companyId(), companyId))
                .map(PlanWithCompany::plan)
                .orElseThrow(() -> new AppException(ErrorCode.LOAD_PLAN_NOT_FOUND));
    }

    /** Gắn kiện đã xếp và kiện không xếp được vào từng phương án (hai câu truy vấn cho cả danh sách). */
    private List<LoadPlanDetailResponse> withItems(List<LoadPlanDetailResponse> plans) {
        Map<Long, LoadPlanDetailResponse> byId = new LinkedHashMap<>();
        for (LoadPlanDetailResponse plan : plans) {
            plan.setPlacements(new ArrayList<>());
            plan.setUnplaced(new ArrayList<>());
            byId.put(plan.getId(), plan);
        }
        if (byId.isEmpty()) {
            return plans;
        }
        for (LoadPlanReadRepository.PlacementRow row : readRepository.findPlacements(byId.keySet())) {
            LoadPlanDetailResponse plan = byId.get(row.planId());
            if (plan != null) {
                List<PlacementDetailResponse> placements = plan.getPlacements();
                placements.add(row.placement());
            }
        }
        for (LoadPlanReadRepository.UnplacedRow row : readRepository.findUnplaced(byId.keySet())) {
            LoadPlanDetailResponse plan = byId.get(row.planId());
            if (plan != null) {
                List<UnplacedPackageResponse> unplaced = plan.getUnplaced();
                unplaced.add(row.unplaced());
            }
        }
        return plans;
    }
}
