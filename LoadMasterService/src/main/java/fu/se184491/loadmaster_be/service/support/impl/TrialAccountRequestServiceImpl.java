package fu.se184491.loadmaster_be.service.support.impl;

import fu.se184491.loadmaster_be.client.BrevoClient;
import fu.se184491.loadmaster_be.constant.company.CompanyStatus;
import fu.se184491.loadmaster_be.constant.support.ContactStatus;
import fu.se184491.loadmaster_be.constant.support.TrialRequestStatus;
import fu.se184491.loadmaster_be.dto.PageResponse;
import fu.se184491.loadmaster_be.dto.request.support.ApproveTrialAccountRequest;
import fu.se184491.loadmaster_be.dto.request.support.CreateTrialAccountRequest;
import fu.se184491.loadmaster_be.dto.response.support.TrialAccountRequestResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.billing.SubscriptionPlan;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.support.ContactOfGuest;
import fu.se184491.loadmaster_be.entity.support.TrialAccountRequest;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.billing.SubscriptionPlanRepository;
import fu.se184491.loadmaster_be.repository.company.CompanyRepository;
import fu.se184491.loadmaster_be.repository.support.ContactOfGuestRepository;
import fu.se184491.loadmaster_be.repository.support.TrialAccountRequestRepository;
import fu.se184491.loadmaster_be.service.support.TrialAccountRequestService;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class TrialAccountRequestServiceImpl implements TrialAccountRequestService {

    private final TrialAccountRequestRepository trialAccountRequestRepository;
    private final ContactOfGuestRepository contactOfGuestRepository;
    private final SubscriptionPlanRepository subscriptionPlanRepository;
    private final CompanyRepository companyRepository;
    private final BrevoClient brevoClient;

    @Override
    public TrialAccountRequestResponse createTrialRequest(CreateTrialAccountRequest request, User requestedBy) {
        ContactOfGuest contact = null;
        if (request.getContactId() != null) {
            contact = contactOfGuestRepository.findById(request.getContactId())
                    .orElseThrow(() -> new AppException(ErrorCode.CONTACT_REQUEST_NOT_FOUND));

            // Mark contact as in progress with forward note
            contact.setStatus(ContactStatus.IN_PROGRESS);
            String forwardNote = "\n[Chuyển duyệt tạo tài khoản dùng thử sang System Manager lúc " + Instant.now() + "]";
            contact.setNote(contact.getNote() != null ? contact.getNote() + forwardNote : forwardNote.trim());
            contactOfGuestRepository.save(contact);
        }

        SubscriptionPlan plan = null;
        if (request.getInterestedPlanId() != null) {
            plan = subscriptionPlanRepository.findById(request.getInterestedPlanId()).orElse(null);
        } else if (contact != null && contact.getInterestedPlan() != null) {
            plan = contact.getInterestedPlan();
        }

        TrialAccountRequest trial = TrialAccountRequest.builder()
                .contact(contact)
                .fullName(request.getFullName())
                .email(request.getEmail())
                .phoneNumber(request.getPhoneNumber())
                .companyName(request.getCompanyName() != null ? request.getCompanyName() : (contact != null ? contact.getCompanyName() : null))
                .companyCode(request.getCompanyCode() != null ? request.getCompanyCode() : (contact != null ? contact.getCompanyCode() : null))
                .taxCode(request.getTaxCode() != null ? request.getTaxCode() : (contact != null ? contact.getTaxCode() : null))
                .interestedPlan(plan)
                .status(TrialRequestStatus.PENDING)
                .note(request.getNote())
                .requestedBy(requestedBy)
                .createdAt(Instant.now())
                .build();

        TrialAccountRequest saved = trialAccountRequestRepository.save(trial);
        return mapToResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<TrialAccountRequestResponse> getTrialRequests(String search, TrialRequestStatus status, Pageable pageable) {
        Specification<TrialAccountRequest> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
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

        Page<TrialAccountRequest> page = trialAccountRequestRepository.findAll(spec, pageable);
        return PageResponse.of(page.map(this::mapToResponse));
    }

    @Override
    @Transactional(readOnly = true)
    public TrialAccountRequestResponse getTrialRequestById(Long id) {
        TrialAccountRequest trial = trialAccountRequestRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.TRIAL_REQUEST_NOT_FOUND));
        return mapToResponse(trial);
    }

    @Override
    public TrialAccountRequestResponse approveTrialRequest(Long id, ApproveTrialAccountRequest request, User approvedBy) {
        TrialAccountRequest trial = trialAccountRequestRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.TRIAL_REQUEST_NOT_FOUND));

        if (trial.getStatus() != TrialRequestStatus.PENDING) {
            throw new AppException(ErrorCode.TRIAL_REQUEST_ALREADY_PROCESSED);
        }

        // Create trial company
        String compName = (request.getCompanyName() != null && !request.getCompanyName().trim().isEmpty())
                ? request.getCompanyName().trim()
                : (trial.getCompanyName() != null ? trial.getCompanyName() : "Công ty dùng thử " + trial.getFullName());

        String compCode = (request.getCompanyCode() != null && !request.getCompanyCode().trim().isEmpty())
                ? request.getCompanyCode().trim()
                : (trial.getCompanyCode() != null && !trial.getCompanyCode().trim().isEmpty()
                        ? trial.getCompanyCode().trim()
                        : "COMP-TRIAL-" + System.currentTimeMillis() % 100000);

        String taxCode = (request.getTaxCode() != null && !request.getTaxCode().trim().isEmpty())
                ? request.getTaxCode().trim()
                : (trial.getTaxCode() != null && !trial.getTaxCode().trim().isEmpty()
                        ? trial.getTaxCode().trim()
                        : "TAX-TRIAL-" + System.currentTimeMillis() % 100000);

        Company company = Company.builder()
                .companyCode(compCode)
                .companyName(compName)
                .taxCode(taxCode)
                .billingEmail(trial.getEmail())
                .status(CompanyStatus.ACTIVE)
                .createdAt(Instant.now())
                .build();

        Company savedCompany = companyRepository.save(company);

        trial.setStatus(TrialRequestStatus.APPROVED);
        trial.setApprovedBy(approvedBy);
        trial.setCreatedCompany(savedCompany);
        trial.setResolvedAt(Instant.now());
        if (request.getNote() != null) {
            trial.setNote(trial.getNote() != null ? trial.getNote() + "\n" + request.getNote() : request.getNote());
        }

        if (trial.getContact() != null) {
            ContactOfGuest contact = trial.getContact();
            contact.setStatus(ContactStatus.RESOLVED);
            contact.setResolvedAt(Instant.now());
            contactOfGuestRepository.save(contact);
        }

        // Send email to customer with trial account confirmation
        String emailContent = """
                Xin chào %s,
                
                Yêu cầu đăng ký tài khoản dùng thử của bạn đã được phê duyệt thành công!
                Công ty của bạn: %s
                Mã công ty: %s
                
                Đội ngũ kỹ thuật LoadMaster đang tiến hành khởi tạo môi trường và thông tin đăng nhập chi tiết sẽ được gửi tiếp theo.
                
                Trân trọng,
                Đội ngũ Quản lý Nền tảng LoadMaster
                """.formatted(trial.getFullName(), savedCompany.getCompanyName(), savedCompany.getCompanyCode());

        brevoClient.sendEmail(trial.getEmail(), "LoadMaster - Tài khoản dùng thử của bạn đã được phê duyệt", emailContent);

        TrialAccountRequest updated = trialAccountRequestRepository.save(trial);
        return mapToResponse(updated);
    }

    @Override
    public TrialAccountRequestResponse rejectTrialRequest(Long id, String reason, User rejectedBy) {
        TrialAccountRequest trial = trialAccountRequestRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.TRIAL_REQUEST_NOT_FOUND));

        if (trial.getStatus() != TrialRequestStatus.PENDING) {
            throw new AppException(ErrorCode.TRIAL_REQUEST_ALREADY_PROCESSED);
        }

        trial.setStatus(TrialRequestStatus.REJECTED);
        trial.setApprovedBy(rejectedBy);
        trial.setResolvedAt(Instant.now());
        if (reason != null && !reason.trim().isEmpty()) {
            trial.setNote(trial.getNote() != null ? trial.getNote() + "\n[Lý do từ chối]: " + reason : "[Lý do từ chối]: " + reason);
        }

        if (trial.getContact() != null) {
            ContactOfGuest contact = trial.getContact();
            contact.setStatus(ContactStatus.CLOSED);
            contact.setResolvedAt(Instant.now());
            contactOfGuestRepository.save(contact);
        }

        // Send refusal email
        String emailContent = """
                Xin chào %s,
                
                Cảm ơn bạn đã quan tâm đến giải pháp LoadMaster.
                Về yêu cầu đăng ký dùng thử của bạn, chúng tôi rất tiếc chưa thể phê duyệt vào thời điểm này.
                %s
                
                Nếu có thêm thắc mắc, vui lòng phản hồi email này để được hỗ trợ.
                
                Trân trọng,
                LoadMaster Team
                """.formatted(trial.getFullName(), reason != null ? "Lý do: " + reason : "");

        brevoClient.sendEmail(trial.getEmail(), "LoadMaster - Thông báo về yêu cầu dùng thử", emailContent);

        TrialAccountRequest updated = trialAccountRequestRepository.save(trial);
        return mapToResponse(updated);
    }

    private TrialAccountRequestResponse mapToResponse(TrialAccountRequest entity) {
        return TrialAccountRequestResponse.builder()
                .id(entity.getId())
                .contactId(entity.getContact() != null ? entity.getContact().getId() : null)
                .fullName(entity.getFullName())
                .email(entity.getEmail())
                .phoneNumber(entity.getPhoneNumber())
                .companyName(entity.getCompanyName())
                .companyCode(entity.getCompanyCode())
                .taxCode(entity.getTaxCode())
                .interestedPlanId(entity.getInterestedPlan() != null ? entity.getInterestedPlan().getId() : null)
                .interestedPlanName(entity.getInterestedPlan() != null ? entity.getInterestedPlan().getName() : null)
                .status(entity.getStatus())
                .note(entity.getNote())
                .requestedById(entity.getRequestedBy() != null ? entity.getRequestedBy().getId() : null)
                .requestedByName(entity.getRequestedBy() != null ? entity.getRequestedBy().getFullName() : null)
                .approvedById(entity.getApprovedBy() != null ? entity.getApprovedBy().getId() : null)
                .approvedByName(entity.getApprovedBy() != null ? entity.getApprovedBy().getFullName() : null)
                .createdCompanyId(entity.getCreatedCompany() != null ? entity.getCreatedCompany().getId() : null)
                .createdCompanyName(entity.getCreatedCompany() != null ? entity.getCreatedCompany().getCompanyName() : null)
                .createdAt(entity.getCreatedAt())
                .resolvedAt(entity.getResolvedAt())
                .build();
    }
}
