package fu.se184491.loadmaster_be.service.customer;

import fu.se184491.loadmaster_be.dto.request.customer.CustomerRequest;
import fu.se184491.loadmaster_be.dto.response.customer.CustomerResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface CustomerService {
    CustomerResponse createCustomer(Long companyId, CustomerRequest request);
    CustomerResponse updateCustomer(Long companyId, Long id, CustomerRequest request);
    CustomerResponse getCustomerById(Long companyId, Long id);
    Page<CustomerResponse> getAllCustomers(Long companyId, Pageable pageable);
}
