package fu.se184491.loadmaster_be.service.optimization;

import fu.se184491.loadmaster_be.constant.billing.*;
import fu.se184491.loadmaster_be.entity.billing.CompanySubscription;
import fu.se184491.loadmaster_be.exception.*;
import fu.se184491.loadmaster_be.repository.billing.CompanySubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class OptimizationEntitlementService {
    private final CompanySubscriptionRepository subscriptions;

    @Transactional
    public CompanySubscription requireActive(Long companyId) {
        CompanySubscription sub = subscriptions.findByCompanyIdForEntitlement(companyId)
                .orElseThrow(() -> new AppException(ErrorCode.OPTIMIZATION_ENTITLEMENT_REQUIRED));
        // Preserve the existing subscription wall-clock convention. Do not reinterpret old strings as UTC.
        LocalDateTime now = LocalDateTime.now();
        if (sub.getStatus() != SubscriptionStatus.ACTIVE || sub.getStartedAt() == null
                || sub.getStartedAt().isAfter(now) || sub.getExpiresAt() == null
                || !sub.getExpiresAt().isAfter(now)) {
            throw new AppException(ErrorCode.OPTIMIZATION_ENTITLEMENT_REQUIRED);
        }
        if (sub.getPlan() == null || !Boolean.TRUE.equals(sub.getPlan().getActive())
                || sub.getPlan().getTier() == null || sub.getPlan().getAlgorithmTier() == null) {
            throw new AppException(ErrorCode.OPTIMIZATION_TIER_INVALID);
        }
        AlgorithmTier expected = switch (sub.getPlan().getTier()) {
            case BASIC -> AlgorithmTier.EP_DBLF;
            case PRO -> AlgorithmTier.EP_DBLF_GA;
            case ULTIMATE -> AlgorithmTier.EP_DBLF_GA_AI;
        };
        if (sub.getPlan().getAlgorithmTier() != expected) {
            throw new AppException(ErrorCode.OPTIMIZATION_TIER_INVALID);
        }
        return sub;
    }
}
