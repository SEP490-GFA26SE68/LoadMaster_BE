package fu.se184491.loadmaster_be.controller.planning;

import fu.se184491.loadmaster_be.dto.request.planning.DeliveryRequirementRequest;
import fu.se184491.loadmaster_be.dto.request.planning.UpdateDeliveryRequirementRequest;
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
@PreAuthorize("hasAuthority('DELIVERY_DEMAND_MANAGE')")
public class DeliveryRequirementController {

    private final DeliveryRequirementService deliveryRequirementService;
    private final CurrentUserService currentUserService;

    @PostMapping
    public ResponseEntity<DeliveryRequirementResponse> create(
            @Valid @RequestBody DeliveryRequirementRequest request
    ) {
        User user = currentUserService.getCurrentUser();
        DeliveryRequirementResponse response = deliveryRequirementService.create(
                user.getCompany().getId(), user.getId(), request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(response.getId())
                .toUri();
        return ResponseEntity.created(location).body(response);
    }

    @GetMapping("/{id}")
    public DeliveryRequirementResponse getById(@PathVariable Long id) {
        return deliveryRequirementService.getById(currentUserService.getCurrentCompanyId(), id);
    }

    @GetMapping
    public PageResponse<DeliveryRequirementResponse> list(
            @RequestParam(required = false) DeliveryRequirementStatus status,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime deadlineFrom,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime deadlineTo,
            @PageableDefault(sort = "deadline") Pageable pageable
    ) {
        return PageResponse.of(deliveryRequirementService.list(
                currentUserService.getCurrentCompanyId(),
                status,
                deadlineFrom,
                deadlineTo,
                pageable));
    }

    @PatchMapping("/{id}")
    public DeliveryRequirementResponse update(
            @PathVariable Long id,
            @Valid @RequestBody UpdateDeliveryRequirementRequest request
    ) {
        return deliveryRequirementService.update(
                currentUserService.getCurrentCompanyId(), id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        deliveryRequirementService.delete(currentUserService.getCurrentCompanyId(), id);
        return ResponseEntity.noContent().build();
    }
}
