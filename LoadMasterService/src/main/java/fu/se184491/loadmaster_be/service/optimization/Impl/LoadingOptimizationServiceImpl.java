package fu.se184491.loadmaster_be.service.optimization.Impl;

import fu.se184491.loadmaster_be.client.OptimizationClient;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionStatus;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionTier;
import fu.se184491.loadmaster_be.dto.response.credit.CreditBalanceResponse;
import fu.se184491.loadmaster_be.dto.response.optimization.OptimizationJobStartResponse;
import fu.se184491.loadmaster_be.dto.response.optimization.OptimizationJobStatusResponse;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.billing.CompanySubscriptionRepository;
import fu.se184491.loadmaster_be.repository.optimization.LoadPlanRepository;
import fu.se184491.loadmaster_be.repository.optimization.PackagePlacementRepository;
import fu.se184491.loadmaster_be.repository.trip.TripRepository;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.entity.optimization.LoadPlan;
import fu.se184491.loadmaster_be.entity.optimization.PackagePlacement;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.service.credit.CreditService;
import fu.se184491.loadmaster_be.service.optimization.LoadPlanQueryService;
import fu.se184491.loadmaster_be.service.optimization.LoadingOptimizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class LoadingOptimizationServiceImpl implements LoadingOptimizationService {

    /**
     * Một lần chạy tối ưu 3D (ba phương án) dùng 1 credit. Dịch vụ tối ưu giữ credit khi bắt đầu chạy
     * và hoàn lại khi không ra phương án nào (NO_SOLUTION) hoặc lỗi.
     */
    private static final int CREDIT_PER_RUN = 1;

    private final TripRepository tripRepository;
    private final CurrentUserService currentUserService;
    private final CompanySubscriptionRepository companySubscriptionRepository;
    private final CreditService creditService;
    private final OptimizationClient optimizationClient;
    private final LoadPlanQueryService loadPlanQueryService;
    private final LoadPlanRepository loadPlanRepository;
    private final PackagePlacementRepository packagePlacementRepository;

    @Override
    public OptimizationJobStartResponse startLoadingOptimization(Long tripId) {
        return startLoadingOptimization(tripId, null);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public OptimizationJobStartResponse startLoadingOptimization(Long tripId, Long keepPinsFromPlanId) {
        Long companyId = currentUserService.getCurrentCompanyId();
        Trip trip = tripRepository.findByIdAndCompanyId(tripId, companyId)
                .orElseThrow(() -> new AppException(ErrorCode.TRIP_NOT_FOUND));
        List<Map<String, Object>> pins = keepPinsFromPlanId == null
                ? List.of()
                : pinsOf(trip, keepPinsFromPlanId);

        SubscriptionTier tier = currentTier(companyId);

        // Chặn sớm khi hết credit: không tạo job nào ở dịch vụ tối ưu.
        if (tier != SubscriptionTier.ULTIMATE) {
            CreditBalanceResponse balance = creditService.getBalance(companyId);
            if (balance == null || balance.getBalance() == null || balance.getBalance() < CREDIT_PER_RUN) {
                throw new AppException(ErrorCode.INSUFFICIENT_CREDITS);
            }
        }

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("trip_id", tripId);
        request.put("objective", "MAX_VOLUME");
        request.put("time_limit_sec", 60);
        request.put("company_id", String.valueOf(companyId));
        request.put("subscription_tier", tier.name());
        if (!pins.isEmpty()) {
            // Dịch vụ tối ưu kiểm bộ ghim trước khi tạo job: hỏng thì PINNED_SET_INVALID, không trừ credit
            request.put("pinned_placements", pins);
        }

        String response = optimizationClient.createJob(request);

        return OptimizationJobStartResponse.builder()
                .jobId(optimizationClient.jobIdOf(response))
                .tripId(tripId)
                .subscriptionTier(tier.name())
                .pinnedCount(pins.size())
                .build();
    }

    /**
     * Kiện đã ghim của phương án nguồn, theo tên trường của dịch vụ tối ưu (cm). Phương án phải thuộc chính chuyến
     * này; giữ ghim chỉ khi chuyến Đã lập kế hoạch (như ghim, bỏ ghim).
     */
    private List<Map<String, Object>> pinsOf(Trip trip, Long planId) {
        LoadPlan plan = loadPlanRepository.findById(planId)
                .orElseThrow(() -> new AppException(ErrorCode.LOAD_PLAN_NOT_FOUND));
        Trip planTrip = plan.getJob() == null ? null : plan.getJob().getTrip();
        if (planTrip == null || !Objects.equals(planTrip.getId(), trip.getId())) {
            throw new AppException(ErrorCode.LOAD_PLAN_NOT_FOUND);
        }
        if (trip.getStatus() != TripStatus.PLANNED) {
            throw new AppException(ErrorCode.TRIP_LOCKED);
        }
        List<Map<String, Object>> pins = new ArrayList<>();
        for (PackagePlacement placement : packagePlacementRepository.findByLoadPlanIdAndPinnedTrue(planId)) {
            if (placement.getCargoPackage() == null) {
                continue;
            }
            Map<String, Object> pin = new LinkedHashMap<>();
            pin.put("package_id", placement.getCargoPackage().getId());
            pin.put("pos_x", placement.getPosX());
            pin.put("pos_y", placement.getPosY());
            pin.put("pos_z", placement.getPosZ());
            pin.put("rotation_type", placement.getRotationType() == null ? 0 : placement.getRotationType());
            pin.put("packed_length", placement.getPackedLength());
            pin.put("packed_width", placement.getPackedWidth());
            pin.put("packed_height", placement.getPackedHeight());
            pins.add(pin);
        }
        return pins;
    }

    @Override
    public OptimizationJobStatusResponse getJobStatus(String jobUuid) {
        return loadPlanQueryService.getJobStatus(jobUuid);
    }

    /** Hạng của gói đang hiệu lực (ACTIVE hoặc TRIAL); không có gói hiệu lực thì BASIC. */
    private SubscriptionTier currentTier(Long companyId) {
        return companySubscriptionRepository.findByCompanyId(companyId)
                .filter(sub -> sub.getStatus() == SubscriptionStatus.ACTIVE
                        || sub.getStatus() == SubscriptionStatus.TRIAL)
                .filter(sub -> sub.getPlan() != null && sub.getPlan().getTier() != null)
                .map(sub -> sub.getPlan().getTier())
                .orElse(SubscriptionTier.BASIC);
    }
}
