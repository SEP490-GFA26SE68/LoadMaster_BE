package fu.se184491.loadmaster_be.service.registration.impl;

import fu.se184491.loadmaster_be.client.BrevoClient;
import fu.se184491.loadmaster_be.client.KeycloakAdminClient;
import fu.se184491.loadmaster_be.client.VnPayClient;
import fu.se184491.loadmaster_be.constant.registration.RegistrationStatus;
import fu.se184491.loadmaster_be.dto.request.registration.AcceptPolicyRequest;
import fu.se184491.loadmaster_be.dto.request.registration.GuestRegistrationInitRequest;
import fu.se184491.loadmaster_be.dto.request.registration.InitPaymentRequest;
import fu.se184491.loadmaster_be.dto.request.registration.VerifyOtpRequest;
import fu.se184491.loadmaster_be.dto.response.registration.GuestRegistrationSessionResponse;
import fu.se184491.loadmaster_be.dto.response.registration.PaymentLinkResponse;
import fu.se184491.loadmaster_be.dto.response.registration.ResendOtpResponse;
import fu.se184491.loadmaster_be.entity.registration.GuestRegistrationSession;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.TemporaryPasswordGenerator;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import fu.se184491.loadmaster_be.repository.billing.CompanySubscriptionRepository;
import fu.se184491.loadmaster_be.repository.billing.PaymentTransactionRepository;
import fu.se184491.loadmaster_be.repository.billing.SubscriptionPlanRepository;
import fu.se184491.loadmaster_be.repository.company.CompanyAgreementRepository;
import fu.se184491.loadmaster_be.repository.company.CompanyRepository;
import fu.se184491.loadmaster_be.repository.registration.GuestRegistrationSessionRepository;
import fu.se184491.loadmaster_be.service.credit.CreditService;
import fu.se184491.loadmaster_be.service.registration.GuestRegistrationService;
import fu.se184491.loadmaster_be.service.security.OtpHasher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class GuestRegistrationServiceImpl implements GuestRegistrationService {

    private final GuestRegistrationSessionRepository sessionRepository;
    private final UserRepository userRepository;
    private final CompanyRepository companyRepository;
    private final SubscriptionPlanRepository subscriptionPlanRepository;
    private final CompanySubscriptionRepository companySubscriptionRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final CompanyAgreementRepository companyAgreementRepository;
    private final CreditService creditService;
    private final KeycloakAdminClient keycloakAdminClient;
    private final VnPayClient vnPayClient;
    private final BrevoClient brevoClient;
    private final OtpHasher otpHasher;
    private final TemporaryPasswordGenerator temporaryPasswordGenerator;

    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public GuestRegistrationSessionResponse initRegistration(GuestRegistrationInitRequest request) {
        String email = request.getEmail().trim().toLowerCase();
        String companyCode = request.getCompanyCode().trim().toUpperCase();
        String taxCode = request.getTaxCode().trim();

        if (userRepository.existsByEmail(email)) {
            throw new AppException(ErrorCode.EMAIL_ALREADY_EXISTS, "Email '" + email + "' đã được sử dụng trong hệ thống.");
        }
        if (companyRepository.existsByCompanyCode(companyCode)) {
            throw new AppException(ErrorCode.COMPANY_ALREADY_EXISTS, "Mã công ty '" + companyCode + "' đã tồn tại trong hệ thống.");
        }
        if (companyRepository.existsByTaxCode(taxCode)) {
            throw new AppException(ErrorCode.TAX_CODE_ALREADY_EXISTS, "Mã số thuế '" + taxCode + "' đã tồn tại trong hệ thống.");
        }

        List<RegistrationStatus> pendingStatuses = List.of(
                RegistrationStatus.PENDING_EMAIL_VERIFY,
                RegistrationStatus.EMAIL_VERIFIED,
                RegistrationStatus.POLICY_ACCEPTED,
                RegistrationStatus.PAYMENT_PENDING
        );

        GuestRegistrationSession session = sessionRepository
                .findFirstByEmailAndStatusInOrderByCreatedAtDesc(email, pendingStatuses)
                .orElse(null);

        String otp = String.format("%06d", RANDOM.nextInt(1_000_000));
        String hashedOtp = otpHasher.hash(email, otp);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime otpExpiresAt = now.plusMinutes(10);
        LocalDateTime otpResendAllowedAt = now.plusSeconds(60);

        if (session == null) {
            String token = UUID.randomUUID().toString();
            session = GuestRegistrationSession.builder()
                    .token(token)
                    .email(email)
                    .fullName(request.getFullName().trim())
                    .phoneNumber(request.getPhoneNumber() != null ? request.getPhoneNumber().trim() : null)
                    .companyName(request.getCompanyName().trim())
                    .companyCode(companyCode)
                    .taxCode(taxCode)
                    .status(RegistrationStatus.PENDING_EMAIL_VERIFY)
                    .otpHash(hashedOtp)
                    .otpExpiresAt(otpExpiresAt)
                    .otpResendAllowedAt(otpResendAllowedAt)
                    .createdAt(now)
                    .expiresAt(now.plusHours(3))
                    .build();
        } else {
            session.setFullName(request.getFullName().trim());
            session.setPhoneNumber(request.getPhoneNumber() != null ? request.getPhoneNumber().trim() : null);
            session.setCompanyName(request.getCompanyName().trim());
            session.setCompanyCode(companyCode);
            session.setTaxCode(taxCode);
            session.setOtpHash(hashedOtp);
            session.setOtpExpiresAt(otpExpiresAt);
            session.setOtpResendAllowedAt(otpResendAllowedAt);
            session.setStatus(RegistrationStatus.PENDING_EMAIL_VERIFY);
        }

        GuestRegistrationSession saved = sessionRepository.save(session);

        sendOtpEmail(email, request.getFullName().trim(), otp);

        return mapToResponse(saved, "Mã xác thực OTP đã được gửi đến email " + email);
    }

    @Override
    public GuestRegistrationSessionResponse verifyOtp(VerifyOtpRequest request) {
        GuestRegistrationSession session = sessionRepository.findByToken(request.getToken())
                .orElseThrow(() -> new AppException(ErrorCode.REGISTRATION_SESSION_NOT_FOUND));

        if (session.getExpiresAt() != null && session.getExpiresAt().isBefore(LocalDateTime.now())) {
            session.setStatus(RegistrationStatus.EXPIRED);
            sessionRepository.save(session);
            throw new AppException(ErrorCode.REGISTRATION_SESSION_EXPIRED);
        }

        if (session.getStatus() != RegistrationStatus.PENDING_EMAIL_VERIFY) {
            throw new AppException(ErrorCode.REGISTRATION_INVALID_STATUS, "Phiên đăng ký không ở trạng thái chờ xác thực OTP.");
        }

        LocalDateTime now = LocalDateTime.now();
        if (session.getOtpExpiresAt() == null || session.getOtpExpiresAt().isBefore(now)) {
            throw new AppException(ErrorCode.OTP_EXPIRED);
        }

        String calculatedHash = otpHasher.hash(session.getEmail(), request.getOtp().trim());
        if (!calculatedHash.equals(session.getOtpHash())) {
            throw new AppException(ErrorCode.OTP_INCORRECT);
        }

        session.setStatus(RegistrationStatus.EMAIL_VERIFIED);
        session.setOtpHash(null); // Clear OTP after success
        GuestRegistrationSession saved = sessionRepository.save(session);

        return mapToResponse(saved, "Xác thực email thành công. Vui lòng ký thỏa thuận điều khoản để tiếp tục.");
    }

    @Override
    public ResendOtpResponse resendOtp(String token) {
        GuestRegistrationSession session = sessionRepository.findByToken(token)
                .orElseThrow(() -> new AppException(ErrorCode.REGISTRATION_SESSION_NOT_FOUND));

        if (session.getExpiresAt() != null && session.getExpiresAt().isBefore(LocalDateTime.now())) {
            session.setStatus(RegistrationStatus.EXPIRED);
            sessionRepository.save(session);
            throw new AppException(ErrorCode.REGISTRATION_SESSION_EXPIRED);
        }

        if (session.getStatus() != RegistrationStatus.PENDING_EMAIL_VERIFY) {
            throw new AppException(ErrorCode.REGISTRATION_INVALID_STATUS, "Chỉ có thể gửi lại OTP khi phiên ở trạng thái chờ xác thực.");
        }

        LocalDateTime now = LocalDateTime.now();
        if (session.getOtpResendAllowedAt() != null && session.getOtpResendAllowedAt().isAfter(now)) {
            throw new AppException(ErrorCode.REGISTRATION_RESEND_COOLDOWN);
        }

        String otp = String.format("%06d", RANDOM.nextInt(1_000_000));
        String hashedOtp = otpHasher.hash(session.getEmail(), otp);
        LocalDateTime otpExpiresAt = now.plusMinutes(10);
        LocalDateTime otpResendAllowedAt = now.plusSeconds(60);

        session.setOtpHash(hashedOtp);
        session.setOtpExpiresAt(otpExpiresAt);
        session.setOtpResendAllowedAt(otpResendAllowedAt);
        sessionRepository.save(session);

        sendOtpEmail(session.getEmail(), session.getFullName(), otp);

        return ResendOtpResponse.builder()
                .message("Mã OTP mới đã được gửi đến email " + session.getEmail())
                .otpExpiresAt(otpExpiresAt)
                .otpResendAllowedAt(otpResendAllowedAt)
                .build();
    }

    @Override
    public GuestRegistrationSessionResponse acceptPolicy(AcceptPolicyRequest request, String ipAddress) {
        GuestRegistrationSession session = sessionRepository.findByToken(request.getToken())
                .orElseThrow(() -> new AppException(ErrorCode.REGISTRATION_SESSION_NOT_FOUND));

        if (session.getExpiresAt() != null && session.getExpiresAt().isBefore(LocalDateTime.now())) {
            session.setStatus(RegistrationStatus.EXPIRED);
            sessionRepository.save(session);
            throw new AppException(ErrorCode.REGISTRATION_SESSION_EXPIRED);
        }

        if (session.getStatus() != RegistrationStatus.EMAIL_VERIFIED) {
            throw new AppException(ErrorCode.REGISTRATION_INVALID_STATUS, "Chỉ có thể ký thỏa thuận khi đã xác thực email thành công.");
        }

        session.setStatus(RegistrationStatus.POLICY_ACCEPTED);
        session.setPolicyAcceptedAt(LocalDateTime.now());
        session.setIpAddress(ipAddress);
        GuestRegistrationSession saved = sessionRepository.save(session);

        return mapToResponse(saved, "Đã ghi nhận đồng ý thỏa thuận điều khoản dịch vụ. Vui lòng chọn gói và tiến hành thanh toán.");
    }

    @Override
    public PaymentLinkResponse initPayment(InitPaymentRequest request, String ipAddress) {
        GuestRegistrationSession session = sessionRepository.findByToken(request.getToken())
                .orElseThrow(() -> new AppException(ErrorCode.REGISTRATION_SESSION_NOT_FOUND));

        if (session.getExpiresAt() != null && session.getExpiresAt().isBefore(LocalDateTime.now())) {
            session.setStatus(RegistrationStatus.EXPIRED);
            sessionRepository.save(session);
            throw new AppException(ErrorCode.REGISTRATION_SESSION_EXPIRED);
        }

        if (session.getStatus() != RegistrationStatus.POLICY_ACCEPTED && session.getStatus() != RegistrationStatus.PAYMENT_PENDING) {
            throw new AppException(ErrorCode.REGISTRATION_INVALID_STATUS, "Chỉ có thể khởi tạo thanh toán sau khi đã ký thỏa thuận điều khoản.");
        }

        fu.se184491.loadmaster_be.entity.billing.SubscriptionPlan plan = subscriptionPlanRepository.findById(request.getPlanId())
                .orElseThrow(() -> new AppException(ErrorCode.SUBSCRIPTION_PLAN_NOT_FOUND));

        java.math.BigDecimal unitPrice = plan.getPriceVnd() != null ? plan.getPriceVnd() : java.math.BigDecimal.ZERO;
        java.math.BigDecimal amountVnd = request.getBillingCycle() == fu.se184491.loadmaster_be.constant.billing.BillingCycle.YEARLY
                ? unitPrice.multiply(java.math.BigDecimal.valueOf(12))
                : unitPrice;

        String paymentRef = "REG_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        String orderInfo = "Dang ky goi " + plan.getName() + " - " + session.getCompanyName();

        String paymentUrl = vnPayClient.createPaymentUrl(paymentRef, amountVnd, orderInfo, ipAddress);

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plusHours(3);

        session.setPlan(plan);
        session.setBillingCycle(request.getBillingCycle());
        session.setPaymentRef(paymentRef);
        session.setPaymentGateway(fu.se184491.loadmaster_be.constant.billing.PaymentGateway.VNPAY);
        session.setAmountVnd(amountVnd);
        session.setStatus(RegistrationStatus.PAYMENT_PENDING);
        session.setExpiresAt(expiresAt);
        sessionRepository.save(session);

        return PaymentLinkResponse.builder()
                .paymentUrl(paymentUrl)
                .paymentRef(paymentRef)
                .amountVnd(amountVnd)
                .expiresAt(expiresAt)
                .build();
    }

    @Override
    public Map<String, String> handlePaymentCallback(Map<String, String> params) {
        Map<String, String> response = new java.util.HashMap<>();
        String txnRef = params.get("vnp_TxnRef");

        boolean isMock = (txnRef != null && txnRef.startsWith("MOCK_"))
                || "true".equalsIgnoreCase(params.get("vnp_Mock"))
                || vnPayClient.isMock();

        // 1. Verify Checksum
        if (!isMock) {
            boolean validSignature = vnPayClient.verifyCallback(params);
            if (!validSignature) {
                log.warn("VNPay callback signature verification failed for params: {}", params);
                response.put("RspCode", "97");
                response.put("Message", "Invalid Checksum");
                return response;
            }
        }

        // 2. Find Registration Session
        GuestRegistrationSession session = sessionRepository.findByPaymentRef(txnRef)
                .orElse(null);

        if (session == null) {
            log.warn("VNPay callback order not found for txnRef: {}", txnRef);
            response.put("RspCode", "01");
            response.put("Message", "Order not Found");
            return response;
        }

        // 3. Idempotency Check: Already completed
        if (session.getStatus() == RegistrationStatus.COMPLETED) {
            log.info("Registration session already completed for txnRef: {}", txnRef);
            response.put("RspCode", "00");
            response.put("Message", "Confirm Success");
            return response;
        }

        // 4. Verify Amount
        String vnpAmountStr = params.get("vnp_Amount");
        if (vnpAmountStr != null && session.getAmountVnd() != null) {
            long vnpAmount = Long.parseLong(vnpAmountStr);
            long expectedAmount = session.getAmountVnd().multiply(java.math.BigDecimal.valueOf(100)).longValue();
            if (vnpAmount != expectedAmount) {
                log.warn("VNPay amount mismatch for txnRef {}: expected {} but got {}", txnRef, expectedAmount, vnpAmount);
                response.put("RspCode", "04");
                response.put("Message", "Invalid Amount");
                return response;
            }
        }

        String responseCode = params.get("vnp_ResponseCode");

        // 5. Check response code
        if (!"00".equals(responseCode)) {
            session.setStatus(RegistrationStatus.PAYMENT_FAILED);
            sessionRepository.save(session);
            log.warn("Registration payment failed for txnRef: {} with code: {}", txnRef, responseCode);

            String subject = "LoadMaster - Thông báo giao dịch thanh toán không thành công";
            String content = """
                    Xin chào %s,

                    Giao dịch thanh toán cho yêu cầu đăng ký tài khoản doanh nghiệp %s với mã tham chiếu %s không thành công.

                    Bạn có thể đăng nhập lại trang đăng ký để thực hiện thanh toán lại.

                    Trân trọng,
                    Đội ngũ LoadMaster
                    """.formatted(session.getFullName(), session.getCompanyName(), txnRef);
            brevoClient.sendEmail(session.getEmail(), subject, content);

            response.put("RspCode", "00");
            response.put("Message", "Confirm Success");
            return response;
        }

        // 6. Payment Succeeded -> Complete Registration
        log.info("Registration payment SUCCESS for txnRef: {}, creating company and user", txnRef);

        // a) Create Company
        fu.se184491.loadmaster_be.entity.company.Company company = fu.se184491.loadmaster_be.entity.company.Company.builder()
                .companyCode(session.getCompanyCode())
                .companyName(session.getCompanyName())
                .taxCode(session.getTaxCode())
                .billingEmail(session.getEmail())
                .status(fu.se184491.loadmaster_be.constant.company.CompanyStatus.ACTIVE)
                .createdAt(java.time.Instant.now())
                .build();
        fu.se184491.loadmaster_be.entity.company.Company savedCompany = companyRepository.save(company);

        // b) Create Keycloak Admin User & Local DB User
        String temporaryPassword = temporaryPasswordGenerator.generate();
        String keycloakUserId = null;
        try {
            keycloakUserId = keycloakAdminClient.findUserIdByEmail(session.getEmail());
            if (keycloakUserId == null) {
                keycloakUserId = keycloakAdminClient.createUser(session.getEmail(), session.getFullName());
            } else {
                keycloakAdminClient.updateUserProfile(keycloakUserId, session.getFullName());
            }
            keycloakAdminClient.setPassword(keycloakUserId, temporaryPassword, false);
            keycloakAdminClient.assignRealmRole(keycloakUserId, fu.se184491.loadmaster_be.constant.account.UserRole.ADMIN.name());
        } catch (Exception ex) {
            log.error("Failed to provision Keycloak account for email: {}", session.getEmail(), ex);
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR, "Không thể khởi tạo tài khoản trên hệ thống định danh: " + ex.getMessage());
        }

        fu.se184491.loadmaster_be.entity.account.User user = fu.se184491.loadmaster_be.entity.account.User.builder()
                .keycloakId(keycloakUserId)
                .company(savedCompany)
                .email(session.getEmail())
                .fullName(session.getFullName())
                .phoneNumber(session.getPhoneNumber())
                .userRoleType(fu.se184491.loadmaster_be.constant.account.UserRole.ADMIN)
                .status(fu.se184491.loadmaster_be.constant.account.UserStatus.ACTIVE)
                .build();
        fu.se184491.loadmaster_be.entity.account.User savedUser = userRepository.save(user);

        // c) Create Subscription
        LocalDateTime now = LocalDateTime.now();
        int durationMonths = session.getBillingCycle() == fu.se184491.loadmaster_be.constant.billing.BillingCycle.YEARLY ? 12 : 1;
        fu.se184491.loadmaster_be.entity.billing.CompanySubscription subscription = fu.se184491.loadmaster_be.entity.billing.CompanySubscription.builder()
                .company(savedCompany)
                .plan(session.getPlan())
                .status(fu.se184491.loadmaster_be.constant.billing.SubscriptionStatus.ACTIVE)
                .startedAt(now)
                .expiresAt(now.plusMonths(durationMonths))
                .autoRenew(true)
                .build();
        companySubscriptionRepository.save(subscription);

        // d) Grant Credits
        int credits = (session.getPlan() != null && session.getPlan().getMonthlyCredits() != null && session.getPlan().getMonthlyCredits() > 0)
                ? session.getPlan().getMonthlyCredits()
                : 1000;
        creditService.grantMonthlyCredits(savedCompany.getId(), credits);

        // e) Record Signed Agreement
        fu.se184491.loadmaster_be.entity.company.CompanyAgreement agreement = fu.se184491.loadmaster_be.entity.company.CompanyAgreement.builder()
                .company(savedCompany)
                .user(savedUser)
                .agreementType(fu.se184491.loadmaster_be.constant.company.AgreementType.TRIAL_TERMS)
                .agreementCode("AGR-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                .title("Thỏa thuận điều khoản dịch vụ LoadMaster")
                .content("Doanh nghiệp và người đại diện đã hoàn tất ký thỏa thuận điều khoản dịch vụ trực tuyến trước khi thanh toán.")
                .status(fu.se184491.loadmaster_be.constant.company.AgreementStatus.SIGNED)
                .deadlineAt(now.plusDays(3))
                .signedAt(session.getPolicyAcceptedAt() != null ? session.getPolicyAcceptedAt() : now)
                .signerName(session.getFullName())
                .signerEmail(session.getEmail())
                .signerPhone(session.getPhoneNumber())
                .companyName(savedCompany.getCompanyName())
                .companyCode(savedCompany.getCompanyCode())
                .taxCode(savedCompany.getTaxCode())
                .ipAddress(session.getIpAddress())
                .build();
        companyAgreementRepository.save(agreement);

        // f) Record Payment Transaction
        fu.se184491.loadmaster_be.entity.billing.PaymentTransaction txn = fu.se184491.loadmaster_be.entity.billing.PaymentTransaction.builder()
                .company(savedCompany)
                .gateway(fu.se184491.loadmaster_be.constant.billing.PaymentGateway.VNPAY)
                .gatewayTransactionId(session.getPaymentRef())
                .amountVnd(session.getAmountVnd())
                .status(fu.se184491.loadmaster_be.constant.billing.PaymentStatus.SUCCESS)
                .orderType("GUEST_REGISTRATION")
                .targetId(session.getPlan() != null ? session.getPlan().getId() : null)
                .payload(params.toString())
                .build();
        paymentTransactionRepository.save(txn);

        // g) Generate PDF Invoice and Send Welcome Email
        byte[] invoicePdf = generateInvoicePdf(savedCompany, savedUser, session.getPlan(), session.getBillingCycle(), session.getAmountVnd(), session.getPaymentRef());
        sendWelcomeEmailWithInvoice(savedCompany, savedUser, session.getPlan(), session.getBillingCycle(), temporaryPassword, invoicePdf);

        // h) Complete Session
        session.setStatus(RegistrationStatus.COMPLETED);
        sessionRepository.save(session);

        response.put("RspCode", "00");
        response.put("Message", "Confirm Success");
        return response;
    }

    private byte[] generateInvoicePdf(
            fu.se184491.loadmaster_be.entity.company.Company company,
            fu.se184491.loadmaster_be.entity.account.User user,
            fu.se184491.loadmaster_be.entity.billing.SubscriptionPlan plan,
            fu.se184491.loadmaster_be.constant.billing.BillingCycle billingCycle,
            java.math.BigDecimal amount,
            String paymentRef
    ) {
        try (java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            com.itextpdf.text.Document document = new com.itextpdf.text.Document();
            com.itextpdf.text.pdf.PdfWriter.getInstance(document, out);
            document.open();

            com.itextpdf.text.Font titleFont = com.itextpdf.text.FontFactory.getFont(com.itextpdf.text.FontFactory.HELVETICA_BOLD, 18);
            com.itextpdf.text.Font boldFont = com.itextpdf.text.FontFactory.getFont(com.itextpdf.text.FontFactory.HELVETICA_BOLD, 12);
            com.itextpdf.text.Font normalFont = com.itextpdf.text.FontFactory.getFont(com.itextpdf.text.FontFactory.HELVETICA, 10);

            com.itextpdf.text.Paragraph title = new com.itextpdf.text.Paragraph("LOADMASTER - HOA DON THANH TOAN / INVOICE", titleFont);
            title.setAlignment(com.itextpdf.text.Element.ALIGN_CENTER);
            title.setSpacingAfter(20);
            document.add(title);

            document.add(new com.itextpdf.text.Paragraph("Ma giao dich / Transaction Ref: " + paymentRef, boldFont));
            document.add(new com.itextpdf.text.Paragraph("Ngay / Date: " + java.time.LocalDate.now(), normalFont));
            document.add(new com.itextpdf.text.Paragraph("Cong ty / Company: " + company.getCompanyName(), normalFont));
            document.add(new com.itextpdf.text.Paragraph("Ma so thue / Tax Code: " + company.getTaxCode(), normalFont));
            document.add(new com.itextpdf.text.Paragraph("Email: " + company.getBillingEmail(), normalFont));
            document.add(new com.itextpdf.text.Paragraph("Dai dien / Representative: " + user.getFullName(), normalFont));
            document.add(new com.itextpdf.text.Paragraph("----------------------------------------------------------------------------------------------------", normalFont));

            document.add(new com.itextpdf.text.Paragraph("Goi dich vu / Plan: " + (plan != null ? plan.getName() : "SaaS Plan"), boldFont));
            document.add(new com.itextpdf.text.Paragraph("Chu ky / Billing Cycle: " + (billingCycle != null ? billingCycle.name() : "MONTHLY"), normalFont));
            document.add(new com.itextpdf.text.Paragraph("Tong tien / Amount: " + String.format("%,.0f VND", amount != null ? amount : java.math.BigDecimal.ZERO), boldFont));
            document.add(new com.itextpdf.text.Paragraph("Trang thai / Status: DA THANH TOAN (PAID)", boldFont));

            document.add(new com.itextpdf.text.Paragraph("----------------------------------------------------------------------------------------------------", normalFont));
            com.itextpdf.text.Paragraph footer = new com.itextpdf.text.Paragraph("Cam on ban da su dung dich vu cua LoadMaster Platform!\nWebsite: http://localhost:5173", normalFont);
            footer.setSpacingBefore(30);
            document.add(footer);

            document.close();
            return out.toByteArray();
        } catch (Exception e) {
            log.error("Failed to generate invoice PDF", e);
            return new byte[0];
        }
    }

    private void sendWelcomeEmailWithInvoice(
            fu.se184491.loadmaster_be.entity.company.Company company,
            fu.se184491.loadmaster_be.entity.account.User user,
            fu.se184491.loadmaster_be.entity.billing.SubscriptionPlan plan,
            fu.se184491.loadmaster_be.constant.billing.BillingCycle billingCycle,
            String temporaryPassword,
            byte[] invoicePdf
    ) {
        String subject = "LoadMaster - Thông tin tài khoản quản trị và hóa đơn dịch vụ";
        String content = """
                Xin chào %s,

                Chúc mừng bạn đã hoàn tất đăng ký tài khoản doanh nghiệp trên nền tảng LoadMaster!

                THÔNG TIN DOANH NGHIỆP:
                • Tên công ty: %s
                • Mã công ty: %s
                • Mã số thuế: %s

                THÔNG TIN TÀI KHOẢN QUẢN TRỊ:
                • Tên đăng nhập / Email: %s
                • Mật khẩu tạm thời: %s
                • Vai trò: Quản trị viên Doanh nghiệp (Company Admin)

                THÔNG TIN GÓI DỊCH VỤ:
                • Gói: %s
                • Chu kỳ thanh toán: %s

                HƯỚNG DẪN ĐĂNG NHẬP:
                Vui lòng truy cập đường dẫn bên dưới để đăng nhập và đổi mật khẩu ở lần đầu tiên:
                http://localhost:5173/dang-nhap

                Hóa đơn thanh toán chi tiết được đính kèm cùng email này (file PDF).

                Trân trọng,
                Đội ngũ Quản trị Nền tảng LoadMaster
                """.formatted(
                user.getFullName(),
                company.getCompanyName(),
                company.getCompanyCode(),
                company.getTaxCode(),
                user.getEmail(),
                temporaryPassword,
                plan != null ? plan.getName() : "Gói dịch vụ SaaS",
                billingCycle != null ? billingCycle.name() : "MONTHLY"
        );

        brevoClient.sendEmailWithAttachment(user.getEmail(), subject, content, "hoa-don-loadmaster.pdf", invoicePdf);
    }

    @Override
    public GuestRegistrationSessionResponse getSessionStatus(String token) {
        GuestRegistrationSession session = sessionRepository.findByToken(token)
                .orElseThrow(() -> new AppException(ErrorCode.REGISTRATION_SESSION_NOT_FOUND));

        if (session.getExpiresAt() != null && session.getExpiresAt().isBefore(LocalDateTime.now())
                && session.getStatus() != RegistrationStatus.COMPLETED) {
            session.setStatus(RegistrationStatus.EXPIRED);
            sessionRepository.save(session);
        }

        return mapToResponse(session, null);
    }

    private void sendOtpEmail(String email, String fullName, String otp) {
        String subject = "LoadMaster - Mã OTP xác thực đăng ký tài khoản";
        String content = """
                Xin chào %s,

                Mã OTP xác thực đăng ký tài khoản LoadMaster của bạn là: %s

                Mã này có hiệu lực trong vòng 10 phút. Vui lòng không chia sẻ mã này cho bất kỳ ai.

                Trân trọng,
                Đội ngũ LoadMaster
                """.formatted(fullName, otp);
        brevoClient.sendEmail(email, subject, content);
    }

    private GuestRegistrationSessionResponse mapToResponse(GuestRegistrationSession session, String message) {
        return GuestRegistrationSessionResponse.builder()
                .token(session.getToken())
                .status(session.getStatus())
                .email(session.getEmail())
                .fullName(session.getFullName())
                .phoneNumber(session.getPhoneNumber())
                .companyName(session.getCompanyName())
                .companyCode(session.getCompanyCode())
                .taxCode(session.getTaxCode())
                .planId(session.getPlan() != null ? session.getPlan().getId() : null)
                .planName(session.getPlan() != null ? session.getPlan().getName() : null)
                .billingCycle(session.getBillingCycle() != null ? session.getBillingCycle().name() : null)
                .otpExpiresAt(session.getOtpExpiresAt())
                .expiresAt(session.getExpiresAt())
                .paymentRef(session.getPaymentRef())
                .message(message)
                .build();
    }
}
