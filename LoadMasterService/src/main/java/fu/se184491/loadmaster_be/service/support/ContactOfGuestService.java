package fu.se184491.loadmaster_be.service.support;

import fu.se184491.loadmaster_be.constant.support.ContactRequestType;
import fu.se184491.loadmaster_be.constant.support.ContactStatus;
import fu.se184491.loadmaster_be.dto.request.support.CreateContactOfGuestRequest;
import fu.se184491.loadmaster_be.dto.request.support.UpdateContactOfGuestRequest;
import fu.se184491.loadmaster_be.dto.response.support.ContactOfGuestResponse;

import fu.se184491.loadmaster_be.dto.PageResponse;
import fu.se184491.loadmaster_be.dto.request.support.ReplyContactOfGuestRequest;
import fu.se184491.loadmaster_be.entity.account.User;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface ContactOfGuestService {

    ContactOfGuestResponse createContact(CreateContactOfGuestRequest request);

    List<ContactOfGuestResponse> getContacts(ContactStatus status, ContactRequestType requestType);

    PageResponse<ContactOfGuestResponse> getContactsPaged(ContactStatus status, ContactRequestType requestType, String search, Pageable pageable);

    ContactOfGuestResponse getContactById(Long id);

    ContactOfGuestResponse updateContact(Long id, UpdateContactOfGuestRequest request);

    ContactOfGuestResponse replyToContact(Long id, ReplyContactOfGuestRequest request, User sender);
}
