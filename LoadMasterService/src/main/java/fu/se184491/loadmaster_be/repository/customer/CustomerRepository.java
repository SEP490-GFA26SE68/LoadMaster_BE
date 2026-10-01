package fu.se184491.loadmaster_be.repository.customer;

import fu.se184491.loadmaster_be.entity.Customer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerRepository extends JpaRepository<Customer, Long> {
    Page<Customer> findByCompanyId(Long companyId, Pageable pageable);
}
