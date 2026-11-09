package fu.se184491.loadmaster_be.repository.driver;

import fu.se184491.loadmaster_be.entity.driver.DeliveryIssue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DeliveryIssueRepository extends JpaRepository<DeliveryIssue, Long> {

    List<DeliveryIssue> findByDeliveryStopIdOrderByReportedAtAsc(Long deliveryStopId);
}
