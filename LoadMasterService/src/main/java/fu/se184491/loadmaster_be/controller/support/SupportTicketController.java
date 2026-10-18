package fu.se184491.loadmaster_be.controller.support;

import fu.se184491.loadmaster_be.constant.support.TicketCategory;
import fu.se184491.loadmaster_be.constant.support.TicketStatus;
import fu.se184491.loadmaster_be.dto.ApiResponse;
import fu.se184491.loadmaster_be.dto.request.support.CreateSupportTicketRequest;
import fu.se184491.loadmaster_be.dto.request.support.UpdateSupportTicketRequest;
import fu.se184491.loadmaster_be.dto.response.support.SupportTicketResponse;
import fu.se184491.loadmaster_be.service.support.SupportTicketService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/support-tickets")
@RequiredArgsConstructor
public class SupportTicketController {

    private final SupportTicketService supportTicketService;

    @PostMapping
    public ResponseEntity<ApiResponse<SupportTicketResponse>> createTicket(
            @Valid @RequestBody CreateSupportTicketRequest request
    ) {
        SupportTicketResponse response = supportTicketService.createTicket(request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success("Support ticket created successfully", response));
    }

    @GetMapping
    public ApiResponse<List<SupportTicketResponse>> getTickets(
            @RequestParam(required = false) TicketStatus status,
            @RequestParam(required = false) TicketCategory category
    ) {
        return ApiResponse.success(
                "Support tickets retrieved successfully",
                supportTicketService.getTickets(status, category)
        );
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('SYSTEM_SUPPORTER', 'SYSTEM_MANAGER', 'SYSTEM_ADMIN') or hasAnyAuthority('SYSTEM_SUPPORTER', 'SYSTEM_MANAGER', 'SYSTEM_ADMIN')")
    public ApiResponse<SupportTicketResponse> updateTicket(
            @PathVariable Long id,
            @Valid @RequestBody UpdateSupportTicketRequest request
    ) {
        return ApiResponse.success(
                "Support ticket updated successfully",
                supportTicketService.updateTicket(id, request)
        );
    }
}
