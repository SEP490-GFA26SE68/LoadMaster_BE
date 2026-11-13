package fu.se184491.loadmaster_be.controller.planning;

import fu.se184491.loadmaster_be.dto.ApiResponse;
import fu.se184491.loadmaster_be.dto.request.planning.DeliveryRequirementRequest;
import fu.se184491.loadmaster_be.dto.request.planning.ExtendDeliveryDeadlineRequest;
import fu.se184491.loadmaster_be.dto.request.planning.UpdateDeliveryRequirementRequest;
import fu.se184491.loadmaster_be.dto.request.planning.UpdateRequirementCoordinatesRequest;
import fu.se184491.loadmaster_be.dto.response.planning.DeliveryRequirementResponse;
import fu.se184491.loadmaster_be.dto.PageResponse;
import fu.se184491.loadmaster_be.constant.planning.DeliveryRequirementStatus;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.service.planning.DeliveryRequirementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;

import java.net.URI;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/delivery-requirements")
@RequiredArgsConstructor
public class DeliveryRequirementController {

    private final DeliveryRequirementService deliveryRequirementService;
    private final CurrentUserService currentUserService;

    @PostMapping
    @PreAuthorize("hasAuthority('DELIVERY_DEMAND_MANAGE')")
    public ResponseEntity<ApiResponse<DeliveryRequirementResponse>> create(
            @Valid @RequestBody DeliveryRequirementRequest request
    ) {
        User user = currentUserService.getCurrentUser();
        DeliveryRequirementResponse response = deliveryRequirementService.create(
                user.getCompany().getId(), user.getId(), request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(response.getId())
                .toUri();
        return ResponseEntity.created(location)
                .body(ApiResponse.success("Tạo yêu cầu giao hàng thành công", response));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('DELIVERY_READ')")
    public ApiResponse<DeliveryRequirementResponse> getById(@PathVariable Long id) {
        DeliveryRequirementResponse response = deliveryRequirementService.getById(
                currentUserService.getCurrentCompanyId(), id);
        return ApiResponse.success("Lấy thông tin yêu cầu giao hàng thành công", response);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('DELIVERY_READ')")
    public PageResponse<DeliveryRequirementResponse> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir,
            @RequestParam(defaultValue = "false") boolean deadlineToExclusive,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime deadlineFrom,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime deadlineTo,
            @PageableDefault(sort = "deadline") Pageable pageable
    ) {
        var sorted = fu.se184491.loadmaster_be.service.cargo.WarehouseListQuery.pageable(pageable, sortBy, sortDir,
                java.util.Map.of("id","id","destination","destination","deadline","deadline","priority","priority",
                        "packages","packages","status","status","trip","trip","createdAt","createdAt"));
        return PageResponse.of(deliveryRequirementService.search(
                currentUserService.getCurrentCompanyId(),
                search, status, priority,
                deadlineFrom, deadlineTo, deadlineToExclusive,
                sorted));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('DISPATCHER')")
    public ApiResponse<DeliveryRequirementResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody UpdateDeliveryRequirementRequest request
    ) {
        DeliveryRequirementResponse response = deliveryRequirementService.update(
                currentUserService.getCurrentCompanyId(), id, request);
        return ApiResponse.success("Cập nhật yêu cầu giao hàng thành công", response);
        }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('DELIVERY_DEMAND_MANAGE')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        deliveryRequirementService.delete(currentUserService.getCurrentCompanyId(), id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/coordinates")
    @PreAuthorize("hasRole('DISPATCHER')")
    public ApiResponse<DeliveryRequirementResponse> updateCoordinates(
            @PathVariable Long id,
            @Valid @RequestBody UpdateRequirementCoordinatesRequest request
    ) {
        Long companyId = currentUserService.getCurrentCompanyId();

        return ApiResponse.success(
                deliveryRequirementService.updateCoordinates(
                        companyId,
                        id,
                        request
                )
        );
    }


    @PatchMapping("/{id}/extend-deadline")
    // Gia hạn sau khi liên hệ khách là việc của quản lý công ty (sự cố đã chuyển lên).
    @PreAuthorize("hasAuthority('DELIVERY_DEMAND_MANAGE') or hasRole('MANAGER')")
    public ApiResponse<DeliveryRequirementResponse> extendDeadline(
            @PathVariable Long id,
            @Valid @RequestBody ExtendDeliveryDeadlineRequest request
    ) {
        User user = currentUserService.getCurrentUser();

        DeliveryRequirementResponse response =
                deliveryRequirementService.extendDeadline(
                        user.getCompany().getId(),
                        user.getId(),
                        id,
                        request
                );

        return ApiResponse.success(
                "Gia hạn giao hàng thành công",
                response
        );
    }

}
