package fu.se184491.loadmaster_be.repository.billing;

import fu.se184491.loadmaster_be.constant.billing.SubscriptionStatus;
import fu.se184491.loadmaster_be.entity.billing.CompanySubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface CompanySubscriptionRepository extends JpaRepository<CompanySubscription, Long> {
    Optional<CompanySubscription> findByCompanyId(Long companyId);
    Optional<CompanySubscription> findByCompanyIdAndStatus(Long companyId, SubscriptionStatus status);
    boolean existsByPlanIdAndStatus(Long planId, SubscriptionStatus status);
    List<CompanySubscription> findByStatusAndExpiresAtBefore(SubscriptionStatus status, LocalDateTime dateTime);
}
