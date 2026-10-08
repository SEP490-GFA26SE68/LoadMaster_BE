package fu.se184491.loadmaster_be.service.order.Impl;

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
import fu.se184491.loadmaster_be.service.order.OrderService;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final CustomerRepository customerRepository;
    private final DeliveryStopRepository deliveryStopRepository;
    private final CompanyRepository companyRepository;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    private OrderResponse mapToResponse(TransportOrder order) {
        return OrderResponse.builder()
                .id(order.getId())
                .companyId(order.getCompany() != null ? order.getCompany().getId() : null)
                .orderCode(order.getOrderCode())
                .customerId(order.getCustomer() != null ? order.getCustomer().getId() : null)
                .customerName(order.getCustomer() != null ? order.getCustomer().getName() : null)
                .deliveryStopId(order.getDeliveryStop() != null ? order.getDeliveryStop().getId() : null)
                .totalWeightKg(order.getTotalWeightKg() != null ? order.getTotalWeightKg() : BigDecimal.ZERO)
                .build();
    }

    private String generateOrderCode(Company company) {
        String companyCode = company.getCompanyCode() != null ? company.getCompanyCode() : "COMP";
        String dateStr = LocalDate.now().format(DATE_FORMATTER);
        String prefix = "ORD-" + companyCode + "-" + dateStr + "-";

        long count = orderRepository.countByCompanyIdAndOrderCodeStartingWith(company.getId(), prefix);
        long seq = count + 1;
        String orderCode = String.format("%s%04d", prefix, seq);

        while (orderRepository.existsByOrderCode(orderCode)) {
            seq++;
            orderCode = String.format("%s%04d", prefix, seq);
        }
        return orderCode;
    }

    @Override
    @Transactional
    public OrderResponse createOrder(Long companyId, OrderRequest request) {
        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new AppException(ErrorCode.COMPANY_NOT_FOUND));

        Customer customer = customerRepository.findById(request.getCustomerId())
                .orElseThrow(() -> new AppException(ErrorCode.CUSTOMER_NOT_FOUND));

        if (!customer.getCompany().getId().equals(companyId)) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }

        DeliveryStop deliveryStop = null;
        if (request.getDeliveryStopId() != null) {
            deliveryStop = deliveryStopRepository.findById(request.getDeliveryStopId())
                    .orElseThrow(() -> new AppException(ErrorCode.DELIVERY_STOP_NOT_FOUND));
        }

        String orderCode = generateOrderCode(company);

        TransportOrder order = TransportOrder.builder()
                .company(company)
                .customer(customer)
                .deliveryStop(deliveryStop)
                .orderCode(orderCode)
                .totalWeightKg(BigDecimal.ZERO)
                .build();

        order = orderRepository.save(order);
        return mapToResponse(order);
    }

    @Override
    @Transactional
    public OrderResponse updateOrder(Long companyId, Long id, OrderRequest request) {
        TransportOrder order = orderRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.ORDER_NOT_FOUND));

        if (!order.getCompany().getId().equals(companyId)) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }

        Customer customer = customerRepository.findById(request.getCustomerId())
                .orElseThrow(() -> new AppException(ErrorCode.CUSTOMER_NOT_FOUND));

        if (!customer.getCompany().getId().equals(companyId)) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }

        DeliveryStop deliveryStop = null;
        if (request.getDeliveryStopId() != null) {
            deliveryStop = deliveryStopRepository.findById(request.getDeliveryStopId())
                    .orElseThrow(() -> new AppException(ErrorCode.DELIVERY_STOP_NOT_FOUND));
        }

        order.setCustomer(customer);
        order.setDeliveryStop(deliveryStop);

        order = orderRepository.save(order);
        return mapToResponse(order);
    }

    @Override
    public OrderResponse getOrderById(Long companyId, Long id) {
        TransportOrder order = orderRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.ORDER_NOT_FOUND));

        if (!order.getCompany().getId().equals(companyId)) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }

        return mapToResponse(order);
    }

    @Override
    public Page<OrderResponse> getAllOrders(Long companyId, Long customerId, Long deliveryStopId, Pageable pageable) {
        Specification<TransportOrder> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("company").get("id"), companyId));
            if (customerId != null) {
                predicates.add(cb.equal(root.get("customer").get("id"), customerId));
            }
            if (deliveryStopId != null) {
                predicates.add(cb.equal(root.get("deliveryStop").get("id"), deliveryStopId));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        return orderRepository.findAll(spec, pageable).map(this::mapToResponse);
    }
}
