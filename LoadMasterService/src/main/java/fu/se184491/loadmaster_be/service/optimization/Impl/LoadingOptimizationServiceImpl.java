package fu.se184491.loadmaster_be.service.optimization.Impl;

import fu.se184491.loadmaster_be.client.OptimizationClient;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionStatus;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionTier;
import fu.se184491.loadmaster_be.dto.response.credit.CreditBalanceResponse;
import fu.se184491.loadmaster_be.dto.response.optimization.OptimizationJobStartResponse;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.billing.CompanySubscriptionRepository;
import fu.se184491.loadmaster_be.repository.trip.TripRepository;
import fu.se184491.loadmaster_be.service.credit.CreditService;
import fu.se184491.loadmaster_be.service.optimization.LoadPlanQueryService;
import fu.se184491.loadmaster_be.service.optimization.LoadingOptimizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class LoadingOptimizationServiceImpl implements LoadingOptimizationService {

    /** Một lần chạy tối ưu 3D dùng 1 credit; dịch vụ tối ưu trừ thật khi chạy. */
    private static final int CREDIT_PER_RUN = 1;

    private final TripRepository tripRepository;
    private final CurrentUserService currentUserService;
    private final CompanySubscriptionRepository companySubscriptionRepository;
    private final CreditService creditService;
    private final OptimizationClient optimizationClient;
    private final LoadPlanQueryService loadPlanQueryService;

    @Override
    public OptimizationJobStartResponse startLoadingOptimization(Long tripId) {
        Long companyId = currentUserService.getCurrentCompanyId();
        tripRepository.findByIdAndCompanyId(tripId, companyId)
                .orElseThrow(() -> new AppException(ErrorCode.TRIP_NOT_FOUND));

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

        String response = optimizationClient.createJob(request);

        return OptimizationJobStartResponse.builder()
                .jobId(optimizationClient.jobIdOf(response))
                .tripId(tripId)
                .subscriptionTier(tier.name())
                .build();
    }

    @Override
    public String getJobStatus(String jobUuid) {
        loadPlanQueryService.requireOwnedJob(jobUuid);
        return optimizationClient.getJobStatus(jobUuid);
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
