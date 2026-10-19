package fu.se184491.loadmaster_be.controller.support;

import fu.se184491.loadmaster_be.constant.support.ContactRequestType;
import fu.se184491.loadmaster_be.constant.support.ContactStatus;
import fu.se184491.loadmaster_be.dto.request.support.CreateContactOfGuestRequest;
import fu.se184491.loadmaster_be.dto.request.support.UpdateContactOfGuestRequest;
import fu.se184491.loadmaster_be.dto.response.support.ContactOfGuestResponse;
import fu.se184491.loadmaster_be.service.support.ContactOfGuestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import fu.se184491.loadmaster_be.dto.PageResponse;
import fu.se184491.loadmaster_be.dto.request.support.ReplyContactOfGuestRequest;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

@RestController
@RequestMapping("/api/contact-guests")
@RequiredArgsConstructor
public class ContactGuestController {

    private final ContactOfGuestService contactOfGuestService;
    private final UserRepository userRepository;

    @PostMapping
    public ResponseEntity<ContactOfGuestResponse> createContact(
            @Valid @RequestBody CreateContactOfGuestRequest request
    ) {
        ContactOfGuestResponse response = contactOfGuestService.createContact(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SYSTEM_SUPPORTER', 'SYSTEM_MANAGER', 'SYSTEM_ADMIN') or hasAnyAuthority('SYSTEM_SUPPORTER', 'SYSTEM_MANAGER', 'SYSTEM_ADMIN')")
    public ResponseEntity<?> getContacts(
            @RequestParam(required = false) ContactStatus status,
            @RequestParam(required = false) ContactRequestType requestType,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false, defaultValue = "false") boolean paged
    ) {
        if (paged || page != null || size != null) {
            int pageNum = page != null ? page : 0;
            int pageSize = size != null ? size : 10;
            Pageable pageable = PageRequest.of(pageNum, pageSize, Sort.by(Sort.Direction.DESC, "createdAt"));
            return ResponseEntity.ok(contactOfGuestService.getContactsPaged(status, requestType, search, pageable));
        }
        return ResponseEntity.ok(contactOfGuestService.getContacts(status, requestType));
    }

    @PostMapping("/{id}/reply")
    @PreAuthorize("hasAnyRole('SYSTEM_SUPPORTER', 'SYSTEM_MANAGER', 'SYSTEM_ADMIN') or hasAnyAuthority('SYSTEM_SUPPORTER', 'SYSTEM_MANAGER', 'SYSTEM_ADMIN')")
    public ResponseEntity<ContactOfGuestResponse> replyContact(
            @PathVariable Long id,
            @Valid @RequestBody ReplyContactOfGuestRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        User sender = null;
        if (jwt != null) {
            String email = jwt.getClaimAsString("email");
            if (email != null) {
                sender = userRepository.findByEmail(email).orElse(null);
            }
        }
        ContactOfGuestResponse response = contactOfGuestService.replyToContact(id, request, sender);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SYSTEM_SUPPORTER', 'SYSTEM_MANAGER', 'SYSTEM_ADMIN') or hasAnyAuthority('SYSTEM_SUPPORTER', 'SYSTEM_MANAGER', 'SYSTEM_ADMIN')")
    public ResponseEntity<ContactOfGuestResponse> getContactById(
            @PathVariable Long id
    ) {
        ContactOfGuestResponse response = contactOfGuestService.getContactById(id);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('SYSTEM_SUPPORTER', 'SYSTEM_MANAGER', 'SYSTEM_ADMIN') or hasAnyAuthority('SYSTEM_SUPPORTER', 'SYSTEM_MANAGER', 'SYSTEM_ADMIN')")
    public ResponseEntity<ContactOfGuestResponse> updateContact(
            @PathVariable Long id,
            @RequestBody UpdateContactOfGuestRequest request
    ) {
        ContactOfGuestResponse response = contactOfGuestService.updateContact(id, request);
        return ResponseEntity.ok(response);
    }
}
