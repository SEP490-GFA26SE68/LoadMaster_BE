package fu.se184491.loadmaster_be.service.customer.Impl;

import fu.se184491.loadmaster_be.dto.request.customer.CustomerRequest;
import fu.se184491.loadmaster_be.dto.response.customer.CustomerResponse;
import fu.se184491.loadmaster_be.entity.Customer;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.customer.CustomerRepository;
import fu.se184491.loadmaster_be.service.customer.CustomerService;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CustomerServiceImpl implements CustomerService {

    private final CustomerRepository customerRepository;
    private final EntityManager entityManager;

    private CustomerResponse mapToResponse(Customer entity) {
        return CustomerResponse.builder()
                .id(entity.getId())
                .companyId(entity.getCompany() != null ? entity.getCompany().getId() : null)
                .name(entity.getName())
                .contactPhone(entity.getContactPhone())
                .address(entity.getAddress())
                .build();
    }

    @Override
    @Transactional
    public CustomerResponse createCustomer(Long companyId, CustomerRequest request) {
        Company companyProxy = entityManager.getReference(Company.class, companyId);
        
        Customer customer = Customer.builder()
                .company(companyProxy)
                .name(request.getName())
                .contactPhone(request.getContactPhone())
                .address(request.getAddress())
                .build();

        customer = customerRepository.save(customer);
        return mapToResponse(customer);
    }

    @Override
    @Transactional
    public CustomerResponse updateCustomer(Long companyId, Long id, CustomerRequest request) {
        Customer customer = customerRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.CUSTOMER_NOT_FOUND));

        if (!customer.getCompany().getId().equals(companyId)) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }

        customer.setName(request.getName());
        customer.setContactPhone(request.getContactPhone());
        customer.setAddress(request.getAddress());

        customer = customerRepository.save(customer);
        return mapToResponse(customer);
    }

    @Override
    public CustomerResponse getCustomerById(Long companyId, Long id) {
        Customer customer = customerRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.CUSTOMER_NOT_FOUND));

        if (!customer.getCompany().getId().equals(companyId)) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }

        return mapToResponse(customer);
    }

    @Override
    public Page<CustomerResponse> getAllCustomers(Long companyId, Pageable pageable) {
        return customerRepository.findByCompanyId(companyId, pageable)
                .map(this::mapToResponse);
    }
}
