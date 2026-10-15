package fu.se184491.loadmaster_be.service.support.impl;

import fu.se184491.loadmaster_be.constant.support.ContactRequestType;
import fu.se184491.loadmaster_be.constant.support.ContactStatus;
import fu.se184491.loadmaster_be.dto.request.support.CreateContactOfGuestRequest;
import fu.se184491.loadmaster_be.dto.request.support.UpdateContactOfGuestRequest;
import fu.se184491.loadmaster_be.dto.response.support.ContactOfGuestResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.billing.SubscriptionPlan;
import fu.se184491.loadmaster_be.entity.support.ContactOfGuest;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import fu.se184491.loadmaster_be.repository.billing.SubscriptionPlanRepository;
import fu.se184491.loadmaster_be.repository.support.ContactOfGuestRepository;
import fu.se184491.loadmaster_be.service.support.ContactOfGuestService;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class ContactOfGuestServiceImpl implements ContactOfGuestService {

    private final ContactOfGuestRepository contactOfGuestRepository;
    private final SubscriptionPlanRepository subscriptionPlanRepository;
    private final UserRepository userRepository;

    @Override
    public ContactOfGuestResponse createContact(CreateContactOfGuestRequest request) {
        SubscriptionPlan plan = null;
        if (request.getInterestedPlanId() != null) {
            plan = subscriptionPlanRepository.findById(request.getInterestedPlanId())
                    .orElse(null);
        }

        ContactOfGuest contact = ContactOfGuest.builder()
                .fullName(request.getFullName())
                .email(request.getEmail())
                .phoneNumber(request.getPhoneNumber())
                .companyName(request.getCompanyName())
                .interestedPlan(plan)
                .message(request.getMessage())
                .requestType(request.getRequestType())
                .status(ContactStatus.NEW)
                .createdAt(Instant.now())
                .build();

        ContactOfGuest saved = contactOfGuestRepository.save(contact);
        return mapToResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ContactOfGuestResponse> getContacts(ContactStatus status, ContactRequestType requestType) {
        Specification<ContactOfGuest> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (requestType != null) {
                predicates.add(cb.equal(root.get("requestType"), requestType));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        return contactOfGuestRepository.findAll(spec, Sort.by(Sort.Direction.DESC, "createdAt"))
                .stream()
                .map(this::mapToResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ContactOfGuestResponse getContactById(Long id) {
        ContactOfGuest contact = contactOfGuestRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.CONTACT_REQUEST_NOT_FOUND));
        return mapToResponse(contact);
    }

    @Override
    public ContactOfGuestResponse updateContact(Long id, UpdateContactOfGuestRequest request) {
        ContactOfGuest contact = contactOfGuestRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.CONTACT_REQUEST_NOT_FOUND));

        if (request.getStatus() != null) {
            contact.setStatus(request.getStatus());
            if ((request.getStatus() == ContactStatus.RESOLVED || request.getStatus() == ContactStatus.CLOSED)
                    && contact.getResolvedAt() == null) {
                contact.setResolvedAt(Instant.now());
            }
        }

        if (request.getAssignedToId() != null) {
            User supporter = userRepository.findById(request.getAssignedToId())
                    .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
            contact.setAssignedTo(supporter);
        }

        if (request.getNote() != null) {
            contact.setNote(request.getNote());
        }

        ContactOfGuest updated = contactOfGuestRepository.save(contact);
        return mapToResponse(updated);
    }

    private ContactOfGuestResponse mapToResponse(ContactOfGuest entity) {
        return ContactOfGuestResponse.builder()
                .id(entity.getId())
                .fullName(entity.getFullName())
                .email(entity.getEmail())
                .phoneNumber(entity.getPhoneNumber())
                .companyName(entity.getCompanyName())
                .interestedPlanId(entity.getInterestedPlan() != null ? entity.getInterestedPlan().getId() : null)
                .interestedPlanName(entity.getInterestedPlan() != null ? entity.getInterestedPlan().getName() : null)
                .assignedToId(entity.getAssignedTo() != null ? entity.getAssignedTo().getId() : null)
                .assignedToName(entity.getAssignedTo() != null ? entity.getAssignedTo().getFullName() : null)
                .message(entity.getMessage())
                .requestType(entity.getRequestType())
                .status(entity.getStatus())
                .note(entity.getNote())
                .createdAt(entity.getCreatedAt())
                .resolvedAt(entity.getResolvedAt())
                .build();
    }
}
