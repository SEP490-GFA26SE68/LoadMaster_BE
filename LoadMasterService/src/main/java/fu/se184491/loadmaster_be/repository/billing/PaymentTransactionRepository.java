package fu.se184491.loadmaster_be.repository.billing;

import fu.se184491.loadmaster_be.constant.billing.PaymentStatus;
import fu.se184491.loadmaster_be.entity.billing.PaymentTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Optional;

@Repository
public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long> {
    Optional<PaymentTransaction> findByGatewayTransactionId(String gatewayTransactionId);
    boolean existsByGatewayTransactionId(String gatewayTransactionId);

    /** Thanh toán mới nhất của công ty theo trạng thái và loại đơn (gói còn chờ trả). */
    Optional<PaymentTransaction> findFirstByCompanyIdAndStatusAndOrderTypeInOrderByIdDesc(
            Long companyId, PaymentStatus status, Collection<String> orderTypes);
}
