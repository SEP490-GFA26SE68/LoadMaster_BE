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
import fu.se184491.loadmaster_be.client.BrevoClient;
import fu.se184491.loadmaster_be.constant.support.TrialRequestStatus;
import fu.se184491.loadmaster_be.dto.PageResponse;
import fu.se184491.loadmaster_be.dto.request.support.ReplyContactOfGuestRequest;
import fu.se184491.loadmaster_be.repository.support.TrialAccountRequestRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
    private final TrialAccountRequestRepository trialAccountRequestRepository;
    private final BrevoClient brevoClient;

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
                .companyCode(request.getCompanyCode())
                .taxCode(request.getTaxCode())
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
    public PageResponse<ContactOfGuestResponse> getContactsPaged(
            ContactStatus status,
            ContactRequestType requestType,
            String search,
            Pageable pageable
    ) {
        Specification<ContactOfGuest> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (requestType != null) {
                predicates.add(cb.equal(root.get("requestType"), requestType));
            }
            if (search != null && !search.trim().isEmpty()) {
                String pattern = "%" + search.trim().toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("fullName")), pattern),
                        cb.like(cb.lower(root.get("email")), pattern),
                        cb.like(cb.lower(root.get("phoneNumber")), pattern),
                        cb.like(cb.lower(root.get("companyName")), pattern)
                ));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Page<ContactOfGuest> page = contactOfGuestRepository.findAll(spec, pageable);
        return PageResponse.of(page.map(this::mapToResponse));
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
        return updateContact(id, request, null);
    }

    @Override
    public ContactOfGuestResponse updateContact(Long id, UpdateContactOfGuestRequest request, User currentUser) {
        ContactOfGuest contact = contactOfGuestRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.CONTACT_REQUEST_NOT_FOUND));

        if (request.getStatus() != null) {
            if (contact.getRequestType() == ContactRequestType.DEMO_REQUEST
                    && (request.getStatus() == ContactStatus.RESOLVED || request.getStatus() == ContactStatus.CLOSED)) {
                boolean hasApprovedTrial = trialAccountRequestRepository.findByContact_Id(id)
                        .map(t -> t.getStatus() == TrialRequestStatus.APPROVED)
                        .orElse(false);
                if (!hasApprovedTrial) {
                    throw new AppException(ErrorCode.CANNOT_DIRECTLY_APPROVE_DEMO_REQUEST);
                }
            }

            contact.setStatus(request.getStatus());
            if ((request.getStatus() == ContactStatus.RESOLVED || request.getStatus() == ContactStatus.CLOSED)
                    && contact.getResolvedAt() == null) {
                contact.setResolvedAt(Instant.now());
            }
        }

        if (request.getAssignedToId() != null) {
            User supporter = userRepository.findById(request.getAssignedToId())
                    .orElse(null);
            if (supporter == null && currentUser != null) {
                supporter = currentUser;
            }
            if (supporter != null) {
                contact.setAssignedTo(supporter);
            }
        } else if (request.getStatus() == ContactStatus.IN_PROGRESS && contact.getAssignedTo() == null && currentUser != null) {
            contact.setAssignedTo(currentUser);
        }

        if (request.getNote() != null) {
            contact.setNote(request.getNote());
        }

        ContactOfGuest updated = contactOfGuestRepository.save(contact);
        return mapToResponse(updated);
    }

    @Override
    public ContactOfGuestResponse replyToContact(Long id, ReplyContactOfGuestRequest request, User sender) {
        ContactOfGuest contact = contactOfGuestRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.CONTACT_REQUEST_NOT_FOUND));

        String subject = request.getSubject() != null && !request.getSubject().trim().isEmpty()
                ? request.getSubject().trim()
                : "Phản hồi từ LoadMaster về yêu cầu liên hệ #" + contact.getId();

        brevoClient.sendEmail(contact.getEmail(), subject, request.getMessage());

        if (contact.getStatus() == ContactStatus.NEW) {
            contact.setStatus(ContactStatus.IN_PROGRESS);
        }
        if (sender != null && contact.getAssignedTo() == null) {
            contact.setAssignedTo(sender);
        }

        String replyLog = "\n[Phản hồi email lúc " + Instant.now() + "]: " + request.getMessage();
        contact.setNote(contact.getNote() != null ? contact.getNote() + replyLog : replyLog.trim());

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
                .companyCode(entity.getCompanyCode())
                .taxCode(entity.getTaxCode())
                .interestedPlanId(entity.getInterestedPlan() != null ? entity.getInterestedPlan().getId() : null)
                .interestedPlanName(entity.getInterestedPlan() != null ? entity.getInterestedPlan().getName() : null)
                .interestedPlanCode(entity.getInterestedPlan() != null ? entity.getInterestedPlan().getPlanCode() : null)
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
