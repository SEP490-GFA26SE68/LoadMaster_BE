package fu.se184491.loadmaster_be.controller.order;

import fu.se184491.loadmaster_be.dto.request.order.OrderRequest;
import fu.se184491.loadmaster_be.dto.response.order.OrderResponse;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.service.order.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @Autowired(required = false)
    private CurrentUserService currentUserService;

    private Long getCurrentCompanyId() {
        try {
            if (currentUserService != null) {
                return currentUserService.getCurrentCompanyId();
            }
        } catch (Exception ignored) {
        }
        return 1L;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyAuthority('ORDER_MANAGE', 'DISPATCHER')")
    public OrderResponse createOrder(@Valid @RequestBody OrderRequest request) {
        return orderService.createOrder(getCurrentCompanyId(), request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ORDER_MANAGE', 'DISPATCHER')")
    public OrderResponse updateOrder(@PathVariable Long id, @Valid @RequestBody OrderRequest request) {
        return orderService.updateOrder(getCurrentCompanyId(), id, request);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ORDER_MANAGE', 'DISPATCHER')")
    public OrderResponse getOrderById(@PathVariable Long id) {
        return orderService.getOrderById(getCurrentCompanyId(), id);
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('ORDER_MANAGE', 'DISPATCHER')")
    public Page<OrderResponse> getAllOrders(
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) Long deliveryStopId,
            Pageable pageable) {
        return orderService.getAllOrders(getCurrentCompanyId(), customerId, deliveryStopId, pageable);
    }
}
