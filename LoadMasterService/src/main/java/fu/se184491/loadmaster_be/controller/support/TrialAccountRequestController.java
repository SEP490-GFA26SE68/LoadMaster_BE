package fu.se184491.loadmaster_be.controller.support;

import fu.se184491.loadmaster_be.constant.support.TrialRequestStatus;
import fu.se184491.loadmaster_be.dto.PageResponse;
import fu.se184491.loadmaster_be.dto.request.support.ApproveTrialAccountRequest;
import fu.se184491.loadmaster_be.dto.request.support.CreateTrialAccountRequest;
import fu.se184491.loadmaster_be.dto.request.support.RejectTrialAccountRequest;
import fu.se184491.loadmaster_be.dto.response.support.TrialAccountRequestResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import fu.se184491.loadmaster_be.service.support.TrialAccountRequestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/trial-requests")
@RequiredArgsConstructor
public class TrialAccountRequestController {

    private final TrialAccountRequestService trialAccountRequestService;
    private final UserRepository userRepository;

    @PostMapping
    @PreAuthorize("hasAnyRole('SYSTEM_SUPPORTER', 'SYSTEM_MANAGER', 'SYSTEM_ADMIN') or hasAnyAuthority('SYSTEM_SUPPORTER', 'SYSTEM_MANAGER', 'SYSTEM_ADMIN')")
    public ResponseEntity<TrialAccountRequestResponse> createTrialRequest(
            @Valid @RequestBody CreateTrialAccountRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        User requester = resolveUser(jwt);
        TrialAccountRequestResponse response = trialAccountRequestService.createTrialRequest(request, requester);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SYSTEM_MANAGER', 'SYSTEM_ADMIN') or hasAnyAuthority('SYSTEM_MANAGER', 'SYSTEM_ADMIN')")
    public ResponseEntity<PageResponse<TrialAccountRequestResponse>> getTrialRequests(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) TrialRequestStatus status,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "10") int size
    ) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        PageResponse<TrialAccountRequestResponse> response = trialAccountRequestService.getTrialRequests(search, status, pageable);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SYSTEM_MANAGER', 'SYSTEM_ADMIN') or hasAnyAuthority('SYSTEM_MANAGER', 'SYSTEM_ADMIN')")
    public ResponseEntity<TrialAccountRequestResponse> getTrialRequestById(@PathVariable Long id) {
        TrialAccountRequestResponse response = trialAccountRequestService.getTrialRequestById(id);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('SYSTEM_MANAGER', 'SYSTEM_ADMIN') or hasAnyAuthority('SYSTEM_MANAGER', 'SYSTEM_ADMIN')")
    public ResponseEntity<TrialAccountRequestResponse> approveTrialRequest(
            @PathVariable Long id,
            @RequestBody(required = false) ApproveTrialAccountRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        User approver = resolveUser(jwt);
        if (request == null) {
            request = new ApproveTrialAccountRequest();
        }
        TrialAccountRequestResponse response = trialAccountRequestService.approveTrialRequest(id, request, approver);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('SYSTEM_MANAGER', 'SYSTEM_ADMIN') or hasAnyAuthority('SYSTEM_ADMIN', 'SYSTEM_MANAGER')")
    public ResponseEntity<TrialAccountRequestResponse> rejectTrialRequest(
            @PathVariable Long id,
            @RequestBody(required = false) RejectTrialAccountRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        User rejecter = resolveUser(jwt);
        String reason = (request != null) ? request.getReason() : null;
        TrialAccountRequestResponse response = trialAccountRequestService.rejectTrialRequest(id, reason, rejecter);
        return ResponseEntity.ok(response);
    }

    private User resolveUser(Jwt jwt) {
        if (jwt == null) return null;
        String email = jwt.getClaimAsString("email");
        if (email != null) {
            return userRepository.findByEmail(email).orElse(null);
        }
        return null;
    }
}
