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

import java.util.List;

@RestController
@RequestMapping("/api/contact-guests")
@RequiredArgsConstructor
public class ContactGuestController {

    private final ContactOfGuestService contactOfGuestService;

    @PostMapping
    public ResponseEntity<ContactOfGuestResponse> createContact(
            @Valid @RequestBody CreateContactOfGuestRequest request
    ) {
        ContactOfGuestResponse response = contactOfGuestService.createContact(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SYSTEM_SUPPORTER', 'SYSTEM_MANAGER', 'SYSTEM_ADMIN') or hasAnyAuthority('SYSTEM_SUPPORTER', 'SYSTEM_MANAGER', 'SYSTEM_ADMIN')")
    public ResponseEntity<List<ContactOfGuestResponse>> getContacts(
            @RequestParam(required = false) ContactStatus status,
            @RequestParam(required = false) ContactRequestType requestType
    ) {
        List<ContactOfGuestResponse> responses = contactOfGuestService.getContacts(status, requestType);
        return ResponseEntity.ok(responses);
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
