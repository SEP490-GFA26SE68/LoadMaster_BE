package fu.se184491.loadmaster_be.repository.order;

import fu.se184491.loadmaster_be.entity.order.TransportOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OrderRepository extends JpaRepository<TransportOrder, Long>, JpaSpecificationExecutor<TransportOrder> {
    Page<TransportOrder> findByCompanyId(Long companyId, Pageable pageable);
    List<TransportOrder> findByCustomerId(Long customerId);
    List<TransportOrder> findByDeliveryStopId(Long deliveryStopId);
    long countByCompanyIdAndOrderCodeStartingWith(Long companyId, String prefix);
    boolean existsByOrderCode(String orderCode);
}
