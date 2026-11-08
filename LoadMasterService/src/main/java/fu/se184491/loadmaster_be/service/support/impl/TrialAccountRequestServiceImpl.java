package fu.se184491.loadmaster_be.service.support.impl;

import fu.se184491.loadmaster_be.client.BrevoClient;
import fu.se184491.loadmaster_be.client.KeycloakAdminClient;
import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.account.UserStatus;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionStatus;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionTier;
import fu.se184491.loadmaster_be.constant.company.CompanyStatus;
import fu.se184491.loadmaster_be.constant.support.ContactStatus;
import fu.se184491.loadmaster_be.constant.support.TrialRequestStatus;
import fu.se184491.loadmaster_be.dto.PageResponse;
import fu.se184491.loadmaster_be.dto.request.support.ApproveTrialAccountRequest;
import fu.se184491.loadmaster_be.dto.request.support.CreateTrialAccountRequest;
import fu.se184491.loadmaster_be.dto.request.support.ManagerApproveTrialRequest;
import fu.se184491.loadmaster_be.dto.response.support.TrialAccountRequestResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.billing.CompanySubscription;
import fu.se184491.loadmaster_be.entity.billing.SubscriptionPlan;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.support.ContactOfGuest;
import fu.se184491.loadmaster_be.entity.support.TrialAccountRequest;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.TemporaryPasswordGenerator;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import fu.se184491.loadmaster_be.repository.billing.CompanySubscriptionRepository;
import fu.se184491.loadmaster_be.repository.billing.SubscriptionPlanRepository;
import fu.se184491.loadmaster_be.repository.company.CompanyRepository;
import fu.se184491.loadmaster_be.repository.support.ContactOfGuestRepository;
import fu.se184491.loadmaster_be.repository.support.TrialAccountRequestRepository;
import fu.se184491.loadmaster_be.service.credit.CreditService;
import fu.se184491.loadmaster_be.service.support.TrialAccountRequestService;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpStatusCodeException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class TrialAccountRequestServiceImpl implements TrialAccountRequestService {

    private final TrialAccountRequestRepository trialAccountRequestRepository;
    private final ContactOfGuestRepository contactOfGuestRepository;
    private final SubscriptionPlanRepository subscriptionPlanRepository;
    private final CompanyRepository companyRepository;
    private final UserRepository userRepository;
    private final CompanySubscriptionRepository companySubscriptionRepository;
    private final CreditService creditService;
    private final KeycloakAdminClient keycloakAdminClient;
    private final TemporaryPasswordGenerator temporaryPasswordGenerator;
    private final fu.se184491.loadmaster_be.helpers.CompanyCodeGenerator companyCodeGenerator;
    private final BrevoClient brevoClient;
    private final fu.se184491.loadmaster_be.service.company.CompanyAgreementService companyAgreementService;

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

        String initialCompCode = request.getCompanyCode() != null ? request.getCompanyCode() : (contact != null ? contact.getCompanyCode() : null);
        String finalCompName = request.getCompanyName() != null ? request.getCompanyName() : (contact != null ? contact.getCompanyName() : null);
        if (initialCompCode == null || initialCompCode.trim().isEmpty()) {
            initialCompCode = companyCodeGenerator.generateUniqueCode(finalCompName != null ? finalCompName : request.getFullName(), companyRepository::existsByCompanyCode);
        }

        TrialAccountRequest trial = TrialAccountRequest.builder()
                .contact(contact)
                .fullName(request.getFullName())
                .email(request.getEmail())
                .phoneNumber(request.getPhoneNumber())
                .companyName(finalCompName)
                .companyCode(initialCompCode)
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
        org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        boolean isAdminOnly = false;
        if (auth != null) {
            boolean hasAdmin = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_SYSTEM_ADMIN") || a.getAuthority().equals("SYSTEM_ADMIN"));
            boolean hasManager = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_SYSTEM_MANAGER") || a.getAuthority().equals("SYSTEM_MANAGER"));
            if (hasAdmin && !hasManager) {
                isAdminOnly = true;
            }
        }
        final boolean enforceManagerApproved = isAdminOnly;

        Specification<TrialAccountRequest> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (enforceManagerApproved) {
                // System Admin chỉ được xem các đơn đã được duyệt bởi System Manager:
                // 1. Không hiển thị đơn PENDING (chưa duyệt của Manager)
                predicates.add(cb.notEqual(root.get("status"), TrialRequestStatus.PENDING));
                // 2. Không hiển thị đơn bị AI từ chối (aiPrecheckStatus == FAILED hoặc handledByName chứa AI)
                predicates.add(cb.or(
                        cb.isNull(root.get("aiPrecheckStatus")),
                        cb.notEqual(root.get("aiPrecheckStatus"), fu.se184491.loadmaster_be.constant.support.AiPrecheckStatus.FAILED)
                ));
                predicates.add(cb.or(
                        cb.isNull(root.get("handledByName")),
                        cb.notLike(cb.lower(root.get("handledByName")), "ai%")
                ));
            }
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
            if (query != null && status == null) {
                query.orderBy(
                        cb.asc(
                                cb.selectCase()
                                        .when(cb.equal(root.get("status"), TrialRequestStatus.PENDING), 1)
                                        .when(cb.equal(root.get("status"), TrialRequestStatus.APPROVED), 2)
                                        .otherwise(3)
                        ),
                        cb.desc(root.get("createdAt"))
                );
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

        if (trial.getStatus() != TrialRequestStatus.PENDING && trial.getStatus() != TrialRequestStatus.PENDING_ADMIN_CREATION) {
            throw new AppException(ErrorCode.TRIAL_REQUEST_ALREADY_PROCESSED);
        }

        // 1. Kiểm tra email người dùng đã tồn tại trong DB chưa
        if (userRepository.existsByEmail(trial.getEmail())) {
            throw new AppException(ErrorCode.EMAIL_ALREADY_EXISTS, "Email '" + trial.getEmail() + "' đã được sử dụng cho một tài khoản khác trong hệ thống.");
        }

        // 2. Chuẩn bị và kiểm tra thông tin công ty (Mã công ty tự sinh tự động từ BE, không nhập tay)
        String compName = (request.getCompanyName() != null && !request.getCompanyName().trim().isEmpty())
                ? request.getCompanyName().trim()
                : (trial.getCompanyName() != null && !trial.getCompanyName().trim().isEmpty()
                        ? trial.getCompanyName().trim()
                        : "Công ty " + trial.getFullName());

        String compCode = trial.getCompanyCode();
        if (compCode == null || compCode.trim().isEmpty() || companyRepository.existsByCompanyCode(compCode.trim())) {
            compCode = companyCodeGenerator.generateUniqueCode(compName, companyRepository::existsByCompanyCode);
            trial.setCompanyCode(compCode);
        } else {
            compCode = compCode.trim();
        }

        String taxCode = (request.getTaxCode() != null && !request.getTaxCode().trim().isEmpty())
                ? request.getTaxCode().trim()
                : (trial.getTaxCode() != null && !trial.getTaxCode().trim().isEmpty() && !companyRepository.existsByTaxCode(trial.getTaxCode().trim())
                        ? trial.getTaxCode().trim()
                        : "TAX-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());

        if (companyRepository.existsByCompanyCode(compCode)) {
            throw new AppException(ErrorCode.COMPANY_ALREADY_EXISTS, "Mã công ty '" + compCode + "' đã tồn tại trong hệ thống.");
        }
        if (companyRepository.existsByTaxCode(taxCode)) {
            throw new AppException(ErrorCode.TAX_CODE_ALREADY_EXISTS, "Mã số thuế '" + taxCode + "' đã tồn tại trong hệ thống.");
        }

        // 3. Tạo Company
        Company company = Company.builder()
                .companyCode(compCode)
                .companyName(compName)
                .taxCode(taxCode)
                .billingEmail(trial.getEmail())
                .status(CompanyStatus.ACTIVE)
                .createdAt(Instant.now())
                .build();
        Company savedCompany = companyRepository.save(company);

        // 4. Tạo hoặc liên kết tài khoản User với vai trò ADMIN (Company Admin)
        String temporaryPassword = temporaryPasswordGenerator.generate();
        String keycloakUserId = null;
        boolean newlyCreatedInKeycloak = false;
        User savedUser = null;

        try {
            String existingKeycloakId = keycloakAdminClient.findUserIdByEmail(trial.getEmail());
            if (existingKeycloakId != null) {
                // Kiểm tra xem ID này đã gắn với DB user nào chưa
                if (userRepository.findByKeycloakId(existingKeycloakId).isPresent()) {
                    throw new AppException(ErrorCode.EMAIL_ALREADY_EXISTS,
                            "Email '" + trial.getEmail() + "' đã được sử dụng cho một tài khoản khác trong hệ thống.");
                }
                log.info("Reusing orphaned Keycloak user {} for email {}", existingKeycloakId, trial.getEmail());
                keycloakUserId = existingKeycloakId;
            } else {
                keycloakUserId = keycloakAdminClient.createUser(trial.getEmail(), trial.getFullName());
                newlyCreatedInKeycloak = true;
            }

            keycloakAdminClient.setPassword(keycloakUserId, temporaryPassword, true);
            keycloakAdminClient.assignRealmRole(keycloakUserId, UserRole.ADMIN.name());

            User user = User.builder()
                    .keycloakId(keycloakUserId)
                    .company(savedCompany)
                    .email(trial.getEmail())
                    .fullName(trial.getFullName())
                    .phoneNumber(trial.getPhoneNumber())
                    .userRoleType(UserRole.ADMIN)
                    .userType(fu.se184491.loadmaster_be.constant.account.UserType.TRIAL)
                    .status(UserStatus.ACTIVE)
                    .build();
            savedUser = userRepository.save(user);
        } catch (AppException ex) {
            if (newlyCreatedInKeycloak && keycloakUserId != null) {
                try {
                    keycloakAdminClient.deleteUser(keycloakUserId);
                } catch (Exception ignored) {}
            }
            throw ex;
        } catch (HttpStatusCodeException ex) {
            log.error("Keycloak HTTP error while approving trial request {}: {} - {}", id, ex.getStatusCode(), ex.getResponseBodyAsString(), ex);
            if (newlyCreatedInKeycloak && keycloakUserId != null) {
                try {
                    keycloakAdminClient.deleteUser(keycloakUserId);
                } catch (Exception ignored) {}
            }
            if (ex.getStatusCode() == HttpStatus.CONFLICT) {
                throw new AppException(ErrorCode.EMAIL_ALREADY_EXISTS, "Email '" + trial.getEmail() + "' đã tồn tại trên hệ thống định danh Keycloak.");
            }
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR, "Lỗi kết nối định danh tài khoản (Keycloak): " + ex.getMessage());
        } catch (Exception ex) {
            log.error("Unexpected error creating user for trial request {}: ", id, ex);
            if (newlyCreatedInKeycloak && keycloakUserId != null) {
                try {
                    keycloakAdminClient.deleteUser(keycloakUserId);
                } catch (Exception ignored) {}
            }
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR, "Không thể khởi tạo tài khoản quản trị: " + ex.getMessage());
        }

        // 5. Kích hoạt gói dịch vụ dùng thử (CompanySubscription)
        SubscriptionPlan plan = null;
        if (request.getPlanId() != null) {
            plan = subscriptionPlanRepository.findById(request.getPlanId()).orElse(null);
        }
        if (plan == null) {
            plan = trial.getInterestedPlan();
        }
        if (plan == null) {
            plan = subscriptionPlanRepository.findByTierAndActiveTrue(SubscriptionTier.PRO)
                    .or(() -> subscriptionPlanRepository.findByTierAndActiveTrue(SubscriptionTier.BASIC))
                    .orElse(null);
        }
        if (plan == null) {
            plan = subscriptionPlanRepository.findByActiveTrue().stream().findFirst().orElse(null);
        }

        int trialDays = (request.getTrialDays() != null && request.getTrialDays() > 0) ? request.getTrialDays() : 14;
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plusDays(trialDays);

        if (plan != null) {
            CompanySubscription subscription = CompanySubscription.builder()
                    .company(savedCompany)
                    .plan(plan)
                    .status(SubscriptionStatus.ACTIVE)
                    .startedAt(now)
                    .expiresAt(expiresAt)
                    .autoRenew(false)
                    .build();
            companySubscriptionRepository.save(subscription);
        }

        // 6. Cấp hạn mức Credit dùng thử
        int credits = (request.getTrialCredits() != null && request.getTrialCredits() > 0)
                ? request.getTrialCredits()
                : (plan != null && plan.getMonthlyCredits() != null && plan.getMonthlyCredits() > 0
                        ? Math.min(plan.getMonthlyCredits(), 1000)
                        : 1000);
        creditService.grantMonthlyCredits(savedCompany.getId(), credits);

        // 7. Tạo Thỏa thuận dịch vụ thử nghiệm (Hạn chót ký 3 ngày)
        companyAgreementService.createTrialAgreement(savedCompany, savedUser, now);

        // 8. Gửi email bàn giao tài khoản trực tiếp đến khách hàng
        String planName = plan != null ? plan.getName() : "Gói dùng thử";
        String emailContent = """
                Xin chào %s,

                Yêu cầu đăng ký trải nghiệm giải pháp LoadMaster của bạn đã được phê duyệt thành công!

                THÔNG TIN DOANH NGHIỆP:
                • Tên công ty: %s
                • Mã công ty: %s
                • Mã số thuế: %s

                THÔNG TIN TÀI KHOẢN ĐĂNG NHẬP:
                • Tên đăng nhập / Email: %s
                • Mật khẩu tạm thời: %s
                • Vai trò tài khoản: Quản trị viên Doanh nghiệp (Company Admin)

                THÔNG TIN GÓI TRẢI NGHIỆM:
                • Gói dịch vụ: %s
                • Thời hạn dùng thử: %d ngày (Có hiệu lực đến %s)
                • Hạn mức tối ưu (Credits): %d credits

                LƯU Ý QUAN TRỌNG VỀ THỎA THUẬN DỊCH VỤ:
                • Bạn vui lòng đăng nhập và thực hiện ký Thỏa thuận dịch vụ thử nghiệm trong vòng 3 ngày kể từ khi nhận được email này.
                • Sau 3 ngày, nếu chưa hoàn tất ký thỏa thuận điện tử, tài khoản quản trị sẽ tự động bị tạm khóa theo quy định bảo mật của nền tảng (kể cả khi thời gian thử nghiệm vẫn còn hiệu lực).

                HƯỚNG DẪN BẮT ĐẦU:
                Vui lòng truy cập hệ thống để đăng nhập và tiến hành đổi mật khẩu ở lần đầu tiên.
                Đường dẫn truy cập: http://localhost:5173/dang-nhap

                Nếu cần hỗ trợ kỹ thuật trong quá trình trải nghiệm, vui lòng liên hệ đội ngũ hỗ trợ của chúng tôi.

                Trân trọng,
                Đội ngũ Quản lý Nền tảng LoadMaster
                """.formatted(
                trial.getFullName(),
                savedCompany.getCompanyName(),
                savedCompany.getCompanyCode(),
                savedCompany.getTaxCode(),
                trial.getEmail(),
                temporaryPassword,
                planName,
                trialDays,
                expiresAt.toLocalDate(),
                credits
        );

        brevoClient.sendEmail(trial.getEmail(), "LoadMaster - Thông tin tài khoản trải nghiệm dịch vụ", emailContent);

        // 8. Cập nhật trạng thái TrialAccountRequest & ContactOfGuest
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

        TrialAccountRequest updated = trialAccountRequestRepository.save(trial);
        return mapToResponse(updated);
    }

    @Override
    @Transactional
    public TrialAccountRequestResponse managerApprove(Long id, ManagerApproveTrialRequest request, User manager) {
        TrialAccountRequest trial = trialAccountRequestRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.TRIAL_REQUEST_NOT_FOUND));

        if (trial.getStatus() != TrialRequestStatus.PENDING) {
            throw new AppException(ErrorCode.TRIAL_REQUEST_ALREADY_PROCESSED);
        }

        // 1. Double check ràng buộc trước khi gửi lên System Admin
        if (trial.getTaxCode() != null && companyRepository.existsByTaxCode(trial.getTaxCode())) {
            throw new AppException(ErrorCode.TAX_CODE_ALREADY_EXISTS,
                    "Doanh nghiệp với Mã số thuế này đã hoạt động trên hệ thống.");
        }
        if (trial.getEmail() != null && userRepository.existsByEmail(trial.getEmail())) {
            throw new AppException(ErrorCode.EMAIL_ALREADY_EXISTS,
                    "Email người đại diện đã được sử dụng cho một tài khoản khác trong hệ thống.");
        }

        // System Manager không sửa thông tin Guest, chỉ lưu ghi chú nội bộ chuyển tiếp nếu có
        if (request != null && request.getManagerNote() != null && !request.getManagerNote().trim().isEmpty()) {
            trial.setManagerNote(request.getManagerNote().trim());
        }

        // 3. Cập nhật trạng thái chuyển giao cho System Admin
        trial.setStatus(TrialRequestStatus.PENDING_ADMIN_CREATION);
        trial.setManagerReviewedBy(manager);
        trial.setHandledByName(manager != null ? manager.getFullName() : "System Manager");

        log.info("System Manager {} approved trial request id={} and forwarded to System Admin",
                manager != null ? manager.getEmail() : "unknown", trial.getId());

        TrialAccountRequest saved = trialAccountRequestRepository.save(trial);
        return mapToResponse(saved);
    }

    @Override
    public TrialAccountRequestResponse managerReject(Long id, String reason, User manager) {
        TrialAccountRequest trial = trialAccountRequestRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.TRIAL_REQUEST_NOT_FOUND));

        if (trial.getStatus() != TrialRequestStatus.PENDING) {
            throw new AppException(ErrorCode.TRIAL_REQUEST_ALREADY_PROCESSED);
        }

        trial.setStatus(TrialRequestStatus.REJECTED);
        trial.setManagerReviewedBy(manager);
        trial.setHandledByName(manager != null ? manager.getFullName() : "System Manager");
        trial.setRejectionReason(reason);
        trial.setResolvedAt(Instant.now());
        if (reason != null && !reason.trim().isEmpty()) {
            trial.setNote(trial.getNote() != null ? trial.getNote() + "\n[Manager từ chối]: " + reason : "[Manager từ chối]: " + reason);
        }

        if (trial.getContact() != null) {
            ContactOfGuest contact = trial.getContact();
            contact.setStatus(ContactStatus.CLOSED);
            contact.setResolvedAt(Instant.now());
            contactOfGuestRepository.save(contact);
        }

        String emailContent = """
                Xin chào %s,

                Cảm ơn bạn đã quan tâm và gửi yêu cầu đăng ký trải nghiệm giải pháp tối ưu logistics LoadMaster.

                Sau khi thẩm định thông tin doanh nghiệp, System Manager xin thông báo yêu cầu của bạn chưa thể được phê duyệt vào thời điểm hiện tại.

                CHI TIẾT LÝ DO:
                %s

                Nếu bạn cần cung cấp bổ sung thông tin hoặc muốn được tư vấn giải pháp phù hợp hơn, xin vui lòng liên hệ lại với đội ngũ hỗ trợ của chúng tôi qua hotline hoặc phản hồi trực tiếp email này.

                Trân trọng,
                Đội ngũ Quản lý Nền tảng LoadMaster
                """.formatted(
                trial.getFullName(),
                (reason != null && !reason.trim().isEmpty()) ? reason.trim() : "Thông tin đăng ký chưa đáp ứng đủ tiêu chuẩn phê duyệt tài khoản doanh nghiệp."
        );

        brevoClient.sendEmail(trial.getEmail(), "LoadMaster - Thông báo về yêu cầu dùng thử", emailContent);

        TrialAccountRequest saved = trialAccountRequestRepository.save(trial);
        return mapToResponse(saved);
    }

    @Override
    public TrialAccountRequestResponse rejectTrialRequest(Long id, String reason, User rejectedBy) {
        TrialAccountRequest trial = trialAccountRequestRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.TRIAL_REQUEST_NOT_FOUND));

        if (trial.getStatus() != TrialRequestStatus.PENDING && trial.getStatus() != TrialRequestStatus.PENDING_ADMIN_CREATION) {
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

                Cảm ơn bạn đã quan tâm và gửi yêu cầu đăng ký trải nghiệm giải pháp tối ưu logistics LoadMaster.

                Sau khi xem xét kỹ lưỡng thông tin, chúng tôi rất tiếc phải thông báo yêu cầu của bạn chưa thể được phê duyệt vào thời điểm hiện tại.

                CHI TIẾT LÝ DO:
                %s

                Nếu bạn cần cung cấp bổ sung thông tin hoặc muốn được tư vấn giải pháp phù hợp hơn, xin vui lòng liên hệ lại với đội ngũ hỗ trợ của chúng tôi qua hotline hoặc phản hồi trực tiếp email này.

                Trân trọng,
                Đội ngũ Quản trị Nền tảng LoadMaster
                """.formatted(
                trial.getFullName(),
                (reason != null && !reason.trim().isEmpty()) ? reason.trim() : "Thông tin đăng ký chưa đáp ứng đủ tiêu chuẩn phê duyệt tài khoản doanh nghiệp."
        );

        brevoClient.sendEmail(trial.getEmail(), "LoadMaster - Thông báo về yêu cầu dùng thử", emailContent);

        TrialAccountRequest updated = trialAccountRequestRepository.save(trial);
        return mapToResponse(updated);
    }

    private TrialAccountRequestResponse mapToResponse(TrialAccountRequest entity) {
        String compCode = entity.getCompanyCode();
        if ((compCode == null || compCode.trim().isEmpty()) && entity.getCompanyName() != null) {
            compCode = companyCodeGenerator.generateUniqueCode(entity.getCompanyName(), companyRepository::existsByCompanyCode);
        }

        return TrialAccountRequestResponse.builder()
                .id(entity.getId())
                .contactId(entity.getContact() != null ? entity.getContact().getId() : null)
                .fullName(entity.getFullName())
                .email(entity.getEmail())
                .phoneNumber(entity.getPhoneNumber())
                .companyName(entity.getCompanyName())
                .companyCode(compCode)
                .taxCode(entity.getTaxCode())
                .companyAddress(entity.getCompanyAddress())
                .interestedPlanId(entity.getInterestedPlan() != null ? entity.getInterestedPlan().getId() : null)
                .interestedPlanName(entity.getInterestedPlan() != null ? entity.getInterestedPlan().getName() : null)
                .status(entity.getStatus())
                .aiPrecheckStatus(entity.getAiPrecheckStatus())
                .aiPrecheckReason(entity.getAiPrecheckReason())
                .handledByName(entity.getHandledByName())
                .rejectionReason(entity.getRejectionReason())
                .note(entity.getNote())
                .managerNote(entity.getManagerNote())
                .trialDays(entity.getTrialDays())
                .trialCredits(entity.getTrialCredits())
                .requestedById(entity.getRequestedBy() != null ? entity.getRequestedBy().getId() : null)
                .requestedByName(entity.getRequestedBy() != null ? entity.getRequestedBy().getFullName() : null)
                .managerReviewedById(entity.getManagerReviewedBy() != null ? entity.getManagerReviewedBy().getId() : null)
                .managerReviewedByName(entity.getManagerReviewedBy() != null ? entity.getManagerReviewedBy().getFullName() : null)
                .managerReviewedByEmail(entity.getManagerReviewedBy() != null ? entity.getManagerReviewedBy().getEmail() : null)
                .approvedById(entity.getApprovedBy() != null ? entity.getApprovedBy().getId() : null)
                .approvedByName(entity.getApprovedBy() != null ? entity.getApprovedBy().getFullName() : null)
                .approvedByEmail(entity.getApprovedBy() != null ? entity.getApprovedBy().getEmail() : null)
                .createdCompanyId(entity.getCreatedCompany() != null ? entity.getCreatedCompany().getId() : null)
                .createdCompanyName(entity.getCreatedCompany() != null ? entity.getCreatedCompany().getCompanyName() : null)
                .createdAt(entity.getCreatedAt())
                .resolvedAt(entity.getResolvedAt())
                .build();
    }
}
