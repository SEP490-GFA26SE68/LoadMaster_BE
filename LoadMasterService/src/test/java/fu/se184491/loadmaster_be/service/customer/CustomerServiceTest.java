package fu.se184491.loadmaster_be.service.customer;

import fu.se184491.loadmaster_be.dto.request.customer.CustomerRequest;
import fu.se184491.loadmaster_be.dto.response.customer.CustomerResponse;
import fu.se184491.loadmaster_be.entity.Customer;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.repository.customer.CustomerRepository;
import fu.se184491.loadmaster_be.service.customer.Impl.CustomerServiceImpl;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class CustomerServiceTest {

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private EntityManager entityManager;

    @InjectMocks
    private CustomerServiceImpl customerService;

    private CustomerRequest request;
    private Customer customer;
    private Company company;

    @BeforeEach
    void setUp() {
        company = Company.builder().id(1L).build();
        request = CustomerRequest.builder()
                .name("John Doe")
                .contactPhone("123456789")
                .address("123 Main St")
                .build();

        customer = Customer.builder()
                .id(1L)
                .company(company)
                .name("John Doe")
                .contactPhone("123456789")
                .address("123 Main St")
                .build();
    }

    @Test
    void createCustomer_Success() {
        when(entityManager.getReference(Company.class, 1L)).thenReturn(company);
        when(customerRepository.save(any(Customer.class))).thenReturn(customer);

        CustomerResponse response = customerService.createCustomer(1L, request);

        assertNotNull(response);
        assertEquals(1L, response.getId());
        assertEquals("John Doe", response.getName());
    }

    @Test
    void updateCustomer_Success() {
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));
        when(customerRepository.save(any(Customer.class))).thenReturn(customer);

        CustomerResponse response = customerService.updateCustomer(1L, 1L, request);

        assertNotNull(response);
        assertEquals("John Doe", response.getName());
    }

    @Test
    void updateCustomer_Unauthorized() {
        Customer otherCustomer = Customer.builder().company(Company.builder().id(2L).build()).build();
        when(customerRepository.findById(1L)).thenReturn(Optional.of(otherCustomer));

        assertThrows(AppException.class, () -> customerService.updateCustomer(1L, 1L, request));
    }

    @Test
    void getCustomerById_Success() {
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));

        CustomerResponse response = customerService.getCustomerById(1L, 1L);

        assertNotNull(response);
        assertEquals(1L, response.getId());
    }

    @Test
    void getAllCustomers_Success() {
        Page<Customer> page = new PageImpl<>(Collections.singletonList(customer));
        when(customerRepository.findByCompanyId(eq(1L), any(Pageable.class))).thenReturn(page);

        Page<CustomerResponse> responses = customerService.getAllCustomers(1L, Pageable.unpaged());

        assertNotNull(responses);
        assertEquals(1, responses.getTotalElements());
    }
}
