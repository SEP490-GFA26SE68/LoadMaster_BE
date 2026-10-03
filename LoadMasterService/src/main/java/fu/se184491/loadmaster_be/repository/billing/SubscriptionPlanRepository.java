package fu.se184491.loadmaster_be.repository.billing;

import fu.se184491.loadmaster_be.constant.billing.SubscriptionTier;
import fu.se184491.loadmaster_be.entity.billing.SubscriptionPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SubscriptionPlanRepository extends JpaRepository<SubscriptionPlan, Long> {
    List<SubscriptionPlan> findByActiveTrue();
    Optional<SubscriptionPlan> findByTierAndActiveTrue(SubscriptionTier tier);
    boolean existsByPlanCode(String planCode);
    boolean existsByTierAndActiveTrue(SubscriptionTier tier);
    boolean existsByTierAndActiveTrueAndIdNot(SubscriptionTier tier, Long id);
}
