package fu.se184491.loadmaster_be.service.order;

import fu.se184491.loadmaster_be.dto.request.order.OrderRequest;
import fu.se184491.loadmaster_be.dto.response.order.OrderResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface OrderService {
    OrderResponse createOrder(Long companyId, OrderRequest request);
    OrderResponse updateOrder(Long companyId, Long id, OrderRequest request);
    OrderResponse getOrderById(Long companyId, Long id);
    Page<OrderResponse> getAllOrders(Long companyId, Long customerId, Long deliveryStopId, Pageable pageable);
}
