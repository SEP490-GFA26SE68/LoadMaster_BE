package fu.se184491.loadmaster_be.controller.customer;

import fu.se184491.loadmaster_be.dto.ApiResponse;
import fu.se184491.loadmaster_be.dto.PageResponse;
import fu.se184491.loadmaster_be.dto.request.customer.CustomerRequest;
import fu.se184491.loadmaster_be.dto.response.customer.CustomerResponse;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.service.customer.CustomerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/customers")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerService customerService;

    // Simulate getting current company id from context. Hardcoded for MVP since security context is usually injected.
    private final CurrentUserService currentUserService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CUSTOMER_MANAGE')")
    public ResponseEntity<ApiResponse<CustomerResponse>> createCustomer(@Valid @RequestBody CustomerRequest request) {
        CustomerResponse response = customerService.createCustomer(currentUserService.getCurrentCompanyId(), request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Customer created successfully", response));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('CUSTOMER_MANAGE')")
    public ApiResponse<CustomerResponse> updateCustomer(@PathVariable Long id, @Valid @RequestBody CustomerRequest request) {
        CustomerResponse response = customerService.updateCustomer(currentUserService.getCurrentCompanyId(), id, request);
        return ApiResponse.success("Customer updated successfully", response);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('CUSTOMER_MANAGE')")
    public ApiResponse<CustomerResponse> getCustomerById(@PathVariable Long id) {
        CustomerResponse response = customerService.getCustomerById(currentUserService.getCurrentCompanyId(), id);
        return ApiResponse.success("Customer retrieved successfully", response);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('CUSTOMER_MANAGE')")
    public PageResponse<CustomerResponse> getAllCustomers(
            @PageableDefault(sort = "name") Pageable pageable
    ) {
        return PageResponse.of(
                customerService.getAllCustomers(
                        currentUserService.getCurrentCompanyId(),
                        pageable
                )
        );
    }
}
