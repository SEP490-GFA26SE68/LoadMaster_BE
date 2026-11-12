package fu.se184491.loadmaster_be.controller.order;

import fu.se184491.loadmaster_be.dto.request.order.OrderRequest;
import fu.se184491.loadmaster_be.dto.response.order.OrderResponse;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.service.order.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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

    private final CurrentUserService currentUserService;

    /** Công ty của người đang đăng nhập; không đăng nhập hay không thuộc công ty nào thì báo lỗi, không mượn công ty khác. */
    private Long getCurrentCompanyId() {
        try {
            return currentUserService.getCurrentCompanyId();
        } catch (IllegalStateException notAuthenticated) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('DISPATCHER')")
    public OrderResponse createOrder(@Valid @RequestBody OrderRequest request) {
        return orderService.createOrder(getCurrentCompanyId(), request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('DISPATCHER')")
    public OrderResponse updateOrder(@PathVariable Long id, @Valid @RequestBody OrderRequest request) {
        return orderService.updateOrder(getCurrentCompanyId(), id, request);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('DISPATCHER')")
    public OrderResponse getOrderById(@PathVariable Long id) {
        return orderService.getOrderById(getCurrentCompanyId(), id);
    }

    @GetMapping
    @PreAuthorize("hasRole('DISPATCHER')")
    public Page<OrderResponse> getAllOrders(
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) Long deliveryStopId,
            Pageable pageable) {
        return orderService.getAllOrders(getCurrentCompanyId(), customerId, deliveryStopId, pageable);
    }
}
