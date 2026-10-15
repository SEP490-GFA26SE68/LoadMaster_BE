package fu.se184491.loadmaster_be.repository.billing;

import fu.se184491.loadmaster_be.constant.billing.InvoiceStatus;
import fu.se184491.loadmaster_be.entity.billing.Invoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InvoiceRepository extends JpaRepository<Invoice, Long> {

    List<Invoice> findByCompanyIdOrderByCreatedAtDesc(Long companyId);

    List<Invoice> findByCompanyIdAndStatusOrderByCreatedAtDesc(Long companyId, InvoiceStatus status);

    List<Invoice> findByStatus(InvoiceStatus status);
}
