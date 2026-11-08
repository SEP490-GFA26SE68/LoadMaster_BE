package fu.se184491.loadmaster_be.service.support.impl;

import fu.se184491.loadmaster_be.client.BrevoClient;
import fu.se184491.loadmaster_be.constant.support.AiPrecheckStatus;
import fu.se184491.loadmaster_be.constant.support.TrialRequestStatus;
import fu.se184491.loadmaster_be.entity.support.TrialAccountRequest;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import fu.se184491.loadmaster_be.repository.company.CompanyRepository;
import fu.se184491.loadmaster_be.repository.support.TrialAccountRequestRepository;
import fu.se184491.loadmaster_be.service.support.TrialPreValidationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class TrialPreValidationServiceImpl implements TrialPreValidationService {

    private static final String AI_HANDLER_NAME = "AI tự xử lý";
    private static final Pattern TAX_CODE_PATTERN = Pattern.compile("^([0-9]{10}|[0-9]{10}-[0-9]{3}|[0-9]{13})$");

    private static final List<TrialRequestStatus> ACTIVE_TRIAL_STATUSES = List.of(
            TrialRequestStatus.PENDING,
            TrialRequestStatus.PENDING_ADMIN_CREATION,
            TrialRequestStatus.APPROVED
    );

    private final CompanyRepository companyRepository;
    private final UserRepository userRepository;
    private final TrialAccountRequestRepository trialAccountRequestRepository;
    private final BrevoClient brevoClient;

    @Override
    @Transactional(readOnly = true)
    public void checkEligibility(String taxCode, String email) {
        String cleanTax = taxCode != null ? taxCode.trim() : "";
        String cleanEmail = email != null ? email.trim().toLowerCase() : "";

        // 1. Kiểm tra cấu trúc Mã số thuế (10 hoặc 13 số)
        if (!TAX_CODE_PATTERN.matcher(cleanTax).matches()) {
            throw new fu.se184491.loadmaster_be.exception.AppException(
                    fu.se184491.loadmaster_be.exception.ErrorCode.INVALID_INPUT,
                    "Mã số thuế không đúng cấu trúc quy định (yêu cầu 10 chữ số hoặc 13 chữ số có/không có dấu gạch ngang).");
        }

        // 2. Kiểm tra trùng lặp MST trong bảng companies
        if (companyRepository.existsByTaxCode(cleanTax)) {
            throw new fu.se184491.loadmaster_be.exception.AppException(
                    fu.se184491.loadmaster_be.exception.ErrorCode.TAX_CODE_ALREADY_EXISTS,
                    "Doanh nghiệp với Mã số thuế này đã hoạt động trên hệ thống LoadMaster.");
        }

        // 3. Kiểm tra trùng lặp MST trong các đơn dùng thử đang chờ hoặc đã cấp
        if (trialAccountRequestRepository.existsByTaxCodeAndStatusIn(cleanTax, ACTIVE_TRIAL_STATUSES)) {
            throw new fu.se184491.loadmaster_be.exception.AppException(
                    fu.se184491.loadmaster_be.exception.ErrorCode.TAX_CODE_ALREADY_EXISTS,
                    "Mã số thuế này đã được đăng ký hoặc đang trong quá trình xét duyệt dùng thử.");
        }

        // 4. Kiểm tra Email đã có tài khoản người dùng chưa
        if (userRepository.existsByEmail(cleanEmail)) {
            throw new fu.se184491.loadmaster_be.exception.AppException(
                    fu.se184491.loadmaster_be.exception.ErrorCode.EMAIL_ALREADY_EXISTS,
                    "Email này đã được sử dụng cho một tài khoản khác trên hệ thống.");
        }

        // 5. Kiểm tra Email trong các đơn dùng thử đang chờ hoặc đã cấp
        if (trialAccountRequestRepository.existsByEmailAndStatusIn(cleanEmail, ACTIVE_TRIAL_STATUSES)) {
            throw new fu.se184491.loadmaster_be.exception.AppException(
                    fu.se184491.loadmaster_be.exception.ErrorCode.EMAIL_ALREADY_EXISTS,
                    "Email này đã gửi yêu cầu dùng thử gần đây và đang được xử lý.");
        }
    }

    @Override
    @Transactional
    public boolean validateAndProcess(TrialAccountRequest request) {
        String taxCode = request.getTaxCode() != null ? request.getTaxCode().trim() : "";
        String email = request.getEmail() != null ? request.getEmail().trim().toLowerCase() : "";

        // 1. Kiểm tra cấu trúc Mã số thuế (10 hoặc 13 số)
        if (!TAX_CODE_PATTERN.matcher(taxCode).matches()) {
            rejectByAi(request, "Mã số thuế không đúng cấu trúc quy định (yêu cầu 10 chữ số hoặc 13 chữ số có/không có dấu gạch ngang).");
            return false;
        }

        // 2. Kiểm tra trùng lặp MST trong bảng companies
        if (companyRepository.existsByTaxCode(taxCode)) {
            rejectByAi(request, "Doanh nghiệp với Mã số thuế này đã hoạt động trên hệ thống LoadMaster.");
            return false;
        }

        // 3. Kiểm tra trùng lặp MST trong các đơn dùng thử đang chờ hoặc đã cấp (loại trừ chính bản ghi hiện tại)
        boolean taxDuplicate = request.getId() != null
                ? trialAccountRequestRepository.existsByTaxCodeAndStatusInAndIdNot(taxCode, ACTIVE_TRIAL_STATUSES, request.getId())
                : trialAccountRequestRepository.existsByTaxCodeAndStatusIn(taxCode, ACTIVE_TRIAL_STATUSES);
        if (taxDuplicate) {
            rejectByAi(request, "Mã số thuế này đã được đăng ký hoặc đang trong quá trình xét duyệt dùng thử.");
            return false;
        }

        // 4. Kiểm tra Email đã có tài khoản người dùng chưa
        if (userRepository.existsByEmail(email)) {
            rejectByAi(request, "Email này đã được sử dụng cho một tài khoản khác trên hệ thống.");
            return false;
        }

        // 5. Kiểm tra Email trong các đơn dùng thử đang chờ hoặc đã cấp (loại trừ chính bản ghi hiện tại)
        boolean emailDuplicate = request.getId() != null
                ? trialAccountRequestRepository.existsByEmailAndStatusInAndIdNot(email, ACTIVE_TRIAL_STATUSES, request.getId())
                : trialAccountRequestRepository.existsByEmailAndStatusIn(email, ACTIVE_TRIAL_STATUSES);
        if (emailDuplicate) {
            rejectByAi(request, "Email này đã gửi yêu cầu dùng thử gần đây và đang được xử lý.");
            return false;
        }

        // Tất cả hợp lệ -> Đạt thẩm định sơ bộ
        passAiCheck(request);
        return true;
    }

    private void rejectByAi(TrialAccountRequest request, String reason) {
        request.setStatus(TrialRequestStatus.REJECTED);
        request.setAiPrecheckStatus(AiPrecheckStatus.FAILED);
        request.setAiPrecheckReason(reason);
        request.setHandledByName(AI_HANDLER_NAME);
        request.setRejectionReason(reason);
        request.setResolvedAt(Instant.now());
        trialAccountRequestRepository.save(request);

        log.info("AI Pre-check FAILED for trial request id={} taxCode={} email={}: {}",
                request.getId(), request.getTaxCode(), request.getEmail(), reason);

        sendRejectionEmail(request, reason);
    }

    private void passAiCheck(TrialAccountRequest request) {
        request.setStatus(TrialRequestStatus.PENDING);
        request.setAiPrecheckStatus(AiPrecheckStatus.PASSED);
        request.setAiPrecheckReason("Thông tin hợp lệ, vượt qua bước kiểm tra tự động");
        request.setHandledByName(null);
        trialAccountRequestRepository.save(request);

        log.info("AI Pre-check PASSED for trial request id={} taxCode={} email={}",
                request.getId(), request.getTaxCode(), request.getEmail());
    }

    private void sendRejectionEmail(TrialAccountRequest request, String reason) {
        String subject = "LoadMaster - Thông báo về yêu cầu đăng ký trải nghiệm giải pháp";
        String content = String.format("""
                Kính gửi Quý khách %s,
                
                Cảm ơn Quý khách và Công ty %s đã quan tâm đến giải pháp tối ưu tải trọng và tuyến vận tải LoadMaster.
                
                Qua quá trình đối soát thông tin tự động, hệ thống nhận thấy yêu cầu đăng ký trải nghiệm của Quý khách chưa thể kích hoạt vì lý do sau:
                
                ⚠️ %s
                
                Nếu có bất kỳ thắc mắc hoặc cần xác minh lại thông tin doanh nghiệp, Quý khách vui lòng liên hệ đội ngũ hỗ trợ qua email hoặc gửi yêu cầu tư vấn tại website LoadMaster.
                
                Trân trọng,
                Đội ngũ LoadMaster
                """,
                request.getFullName() != null ? request.getFullName() : "Đối tác",
                request.getCompanyName() != null ? request.getCompanyName() : "Quý Doanh nghiệp",
                reason);

        try {
            brevoClient.sendEmail(request.getEmail(), subject, content);
        } catch (Exception ex) {
            log.error("Failed to send rejection email via Brevo to {}: {}", request.getEmail(), ex.getMessage());
        }
    }
}

