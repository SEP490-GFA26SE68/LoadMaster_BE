package fu.se184491.loadmaster_be.repository.billing;

import fu.se184491.loadmaster_be.entity.billing.PaymentTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long> {
    Optional<PaymentTransaction> findByGatewayTransactionId(String gatewayTransactionId);
    boolean existsByGatewayTransactionId(String gatewayTransactionId);
}
