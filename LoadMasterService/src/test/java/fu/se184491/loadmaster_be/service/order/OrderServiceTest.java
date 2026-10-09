package fu.se184491.loadmaster_be.service.order;

import fu.se184491.loadmaster_be.dto.request.order.OrderRequest;
import fu.se184491.loadmaster_be.dto.response.order.OrderResponse;
import fu.se184491.loadmaster_be.entity.Customer;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.order.TransportOrder;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.company.CompanyRepository;
import fu.se184491.loadmaster_be.repository.customer.CustomerRepository;
import fu.se184491.loadmaster_be.repository.order.OrderRepository;
import fu.se184491.loadmaster_be.repository.trip.DeliveryStopRepository;
import fu.se184491.loadmaster_be.service.order.Impl.OrderServiceImpl;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private DeliveryStopRepository deliveryStopRepository;

    @Mock
    private CompanyRepository companyRepository;

    @Mock
    private EntityManager entityManager;

    @InjectMocks
    private OrderServiceImpl orderService;

    private Company company;
    private Customer customer;
    private DeliveryStop deliveryStop;
    private TransportOrder order;
    private OrderRequest request;

    @BeforeEach
    void setUp() {
        company = Company.builder()
                .id(1L)
                .companyCode("CMP01")
                .companyName("Test Company")
                .build();

        customer = Customer.builder()
                .id(10L)
                .company(company)
                .name("Acme Corp")
                .build();

        deliveryStop = DeliveryStop.builder()
                .id(20L)
                .stopName("Stop 1")
                .build();

        order = TransportOrder.builder()
                .id(100L)
                .company(company)
                .customer(customer)
                .deliveryStop(deliveryStop)
                .orderCode("ORD-CMP01-20261004-0001")
                .totalWeightKg(BigDecimal.ZERO)
                .build();

        request = OrderRequest.builder()
                .customerId(10L)
                .deliveryStopId(20L)
                .build();
    }

    @Test
    void createOrder_Success_WithDeliveryStop() {
        when(companyRepository.findById(1L)).thenReturn(Optional.of(company));
        when(customerRepository.findById(10L)).thenReturn(Optional.of(customer));
        when(deliveryStopRepository.findById(20L)).thenReturn(Optional.of(deliveryStop));

        String dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String expectedPrefix = "ORD-CMP01-" + dateStr + "-";
        when(orderRepository.countByCompanyIdAndOrderCodeStartingWith(eq(1L), eq(expectedPrefix))).thenReturn(0L);
        when(orderRepository.existsByOrderCode(anyString())).thenReturn(false);

        when(orderRepository.save(any(TransportOrder.class))).thenAnswer(invocation -> {
            TransportOrder saved = invocation.getArgument(0);
            saved.setId(100L);
            return saved;
        });

        OrderResponse response = orderService.createOrder(1L, request);

        assertNotNull(response);
        assertEquals(100L, response.getId());
        assertEquals(1L, response.getCompanyId());
        assertEquals(10L, response.getCustomerId());
        assertEquals("Acme Corp", response.getCustomerName());
        assertEquals(20L, response.getDeliveryStopId());
        assertEquals(BigDecimal.ZERO, response.getTotalWeightKg());
        assertTrue(response.getOrderCode().startsWith(expectedPrefix));
        assertTrue(response.getOrderCode().endsWith("-0001"));

        ArgumentCaptor<TransportOrder> captor = ArgumentCaptor.forClass(TransportOrder.class);
        verify(orderRepository).save(captor.capture());
        TransportOrder captured = captor.getValue();
        assertEquals(BigDecimal.ZERO, captured.getTotalWeightKg());
        assertEquals(customer, captured.getCustomer());
        assertEquals(deliveryStop, captured.getDeliveryStop());
    }

    @Test
    void createOrder_Success_NullableDeliveryStop() {
        request.setDeliveryStopId(null);
        when(companyRepository.findById(1L)).thenReturn(Optional.of(company));
        when(customerRepository.findById(10L)).thenReturn(Optional.of(customer));

        String dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String expectedPrefix = "ORD-CMP01-" + dateStr + "-";
        when(orderRepository.countByCompanyIdAndOrderCodeStartingWith(eq(1L), eq(expectedPrefix))).thenReturn(2L);
        when(orderRepository.existsByOrderCode(anyString())).thenReturn(false);

        when(orderRepository.save(any(TransportOrder.class))).thenAnswer(invocation -> {
            TransportOrder saved = invocation.getArgument(0);
            saved.setId(101L);
            return saved;
        });

        OrderResponse response = orderService.createOrder(1L, request);

        assertNotNull(response);
        assertNull(response.getDeliveryStopId());
        assertEquals(expectedPrefix + "0003", response.getOrderCode());
        verify(deliveryStopRepository, never()).findById(any());
    }

    @Test
    void createOrder_Throws_CustomerNotFound() {
        when(companyRepository.findById(1L)).thenReturn(Optional.of(company));
        when(customerRepository.findById(10L)).thenReturn(Optional.empty());

        AppException ex = assertThrows(AppException.class, () -> orderService.createOrder(1L, request));
        assertEquals(ErrorCode.CUSTOMER_NOT_FOUND, ex.getErrorCode());
        verify(orderRepository, never()).save(any());
    }

    @Test
    void createOrder_Throws_CustomerBelongsToDifferentCompany() {
        Company otherCompany = Company.builder().id(999L).build();
        Customer otherCustomer = Customer.builder().id(10L).company(otherCompany).build();

        when(companyRepository.findById(1L)).thenReturn(Optional.of(company));
        when(customerRepository.findById(10L)).thenReturn(Optional.of(otherCustomer));

        AppException ex = assertThrows(AppException.class, () -> orderService.createOrder(1L, request));
        assertEquals(ErrorCode.UNAUTHORIZED, ex.getErrorCode());
        verify(orderRepository, never()).save(any());
    }

    @Test
    void updateOrder_Success() {
        when(orderRepository.findById(100L)).thenReturn(Optional.of(order));
        when(customerRepository.findById(10L)).thenReturn(Optional.of(customer));
        when(deliveryStopRepository.findById(20L)).thenReturn(Optional.of(deliveryStop));
        when(orderRepository.save(any(TransportOrder.class))).thenAnswer(inv -> inv.getArgument(0));

        OrderResponse response = orderService.updateOrder(1L, 100L, request);

        assertNotNull(response);
        assertEquals(100L, response.getId());
        assertEquals("ORD-CMP01-20261004-0001", response.getOrderCode());
        verify(orderRepository).save(order);
    }

    @Test
    void updateOrder_Throws_OrderNotFound() {
        when(orderRepository.findById(100L)).thenReturn(Optional.empty());

        AppException ex = assertThrows(AppException.class, () -> orderService.updateOrder(1L, 100L, request));
        assertEquals(ErrorCode.ORDER_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void updateOrder_Throws_Unauthorized() {
        Company otherCompany = Company.builder().id(999L).build();
        order.setCompany(otherCompany);
        when(orderRepository.findById(100L)).thenReturn(Optional.of(order));

        AppException ex = assertThrows(AppException.class, () -> orderService.updateOrder(1L, 100L, request));
        assertEquals(ErrorCode.UNAUTHORIZED, ex.getErrorCode());
    }

    @Test
    void getOrderById_Success() {
        when(orderRepository.findById(100L)).thenReturn(Optional.of(order));

        OrderResponse response = orderService.getOrderById(1L, 100L);

        assertNotNull(response);
        assertEquals(100L, response.getId());
        assertEquals("ORD-CMP01-20261004-0001", response.getOrderCode());
    }

    @Test
    void getOrderById_Throws_OrderNotFound() {
        when(orderRepository.findById(100L)).thenReturn(Optional.empty());

        AppException ex = assertThrows(AppException.class, () -> orderService.getOrderById(1L, 100L));
        assertEquals(ErrorCode.ORDER_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void getAllOrders_Success() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<TransportOrder> page = new PageImpl<>(Collections.singletonList(order), pageable, 1);
        when(orderRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(page);

        Page<OrderResponse> response = orderService.getAllOrders(1L, 10L, 20L, pageable);

        assertNotNull(response);
        assertEquals(1, response.getTotalElements());
        assertEquals(100L, response.getContent().get(0).getId());
    }
}
