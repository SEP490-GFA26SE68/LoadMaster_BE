package fu.se184491.loadmaster_be.service.company.Impl;

import fu.se184491.loadmaster_be.constant.account.UserStatus;
import fu.se184491.loadmaster_be.constant.company.AgreementStatus;
import fu.se184491.loadmaster_be.constant.company.AgreementType;
import fu.se184491.loadmaster_be.dto.request.company.SignAgreementRequest;
import fu.se184491.loadmaster_be.dto.response.company.CompanyAgreementResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.company.CompanyAgreement;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import fu.se184491.loadmaster_be.repository.company.CompanyAgreementRepository;
import fu.se184491.loadmaster_be.service.company.CompanyAgreementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class CompanyAgreementServiceImpl implements CompanyAgreementService {

    private final CompanyAgreementRepository agreementRepository;
    private final UserRepository userRepository;

    public static final String STANDARD_TRIAL_TERMS = """
            # THỎA THUẬN SỬ DỤNG DỊCH VỤ THỬ NGHIỆM NỀN TẢNG LOADMASTER
            
            **Điều 1: Mục đích & Đối tượng áp dụng**
            1.1. Thỏa thuận này áp dụng đối với các Doanh nghiệp đăng ký trải nghiệm giải pháp tối ưu hóa vận tải và quản lý kho vận trên nền tảng SaaS LoadMaster.
            1.2. Bên đăng ký xác nhận là người đại diện hợp pháp của Doanh nghiệp theo thông tin đã cung cấp.
            
            **Điều 2: Thời hạn & Quy định thử nghiệm**
            2.1. Doanh nghiệp được cấp quyền trải nghiệm hệ thống trong thời gian dùng thử tiêu chuẩn (14 ngày).
            2.2. **Quy định bắt buộc:** Trong vòng 03 ngày (72 giờ) kể từ khi được kích hoạt tài khoản, Quản trị viên Doanh nghiệp (Company Admin) bắt buộc phải thực hiện ký xác nhận Thỏa thuận này.
            2.3. Hết thời hạn 03 ngày, nếu Doanh nghiệp chưa hoàn tất ký thỏa thuận điện tử, hệ thống sẽ tự động tạm khóa tài khoản quản trị để bảo đảm an toàn thông tin và tính pháp lý của nền tảng, kể cả khi thời hạn gói dùng thử vẫn còn hiệu lực.
            
            **Điều 3: Hạn mức sử dụng & Trách nhiệm dữ liệu**
            3.1. Nền tảng cấp hạn mức Credits dùng thử để thực hiện các tác vụ tối ưu hóa xếp dỡ (3D packing) và định tuyến giao hàng.
            3.2. Doanh nghiệp cam kết không sử dụng nền tảng cho các mục đích vi phạm pháp luật, không can thiệp trái phép vào hệ thống hoặc lạm dụng tài nguyên tính toán.
            3.3. Dữ liệu chuyến hàng, phương tiện và hàng hóa của Doanh nghiệp được bảo mật tuyệt đối theo chính sách bảo mật của LoadMaster.
            
            **Điều 4: Hiệu lực thỏa thuận**
            Thỏa thuận này có hiệu lực kể từ thời điểm Người đại diện Doanh nghiệp bấm xác nhận ký điện tử trên hệ thống.
            """;

    @Override
    public CompanyAgreement createTrialAgreement(Company company, User adminUser, LocalDateTime approvedAt) {
        LocalDateTime deadline = approvedAt.plusDays(3);
        String code = "AGR-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        CompanyAgreement agreement = CompanyAgreement.builder()
                .company(company)
                .user(adminUser)
                .agreementType(AgreementType.TRIAL_TERMS)
                .agreementCode(code)
                .title("Thỏa thuận Sử dụng Dịch vụ Thử nghiệm LoadMaster")
                .content(STANDARD_TRIAL_TERMS)
                .status(AgreementStatus.PENDING)
                .deadlineAt(deadline)
                .companyName(company.getCompanyName())
                .companyCode(company.getCompanyCode())
                .taxCode(company.getTaxCode())
                .signerName(adminUser != null ? adminUser.getFullName() : null)
                .signerEmail(adminUser != null ? adminUser.getEmail() : null)
                .signerPhone(adminUser != null ? adminUser.getPhoneNumber() : null)
                .build();

        return agreementRepository.save(agreement);
    }

    @Override
    @Transactional(readOnly = true)
    public CompanyAgreementResponse getMyAgreement(User currentUser) {
        if (currentUser.getCompany() == null || currentUser.getCompany().getId() == null) {
            throw new AppException(ErrorCode.AGREEMENT_NOT_FOUND, "Người dùng chưa thuộc doanh nghiệp nào");
        }

        CompanyAgreement agreement = agreementRepository
                .findFirstByCompanyIdOrderByCreatedAtDesc(currentUser.getCompany().getId())
                .orElseThrow(() -> new AppException(ErrorCode.AGREEMENT_NOT_FOUND));

        return toResponse(agreement);
    }

    @Override
    public CompanyAgreementResponse signAgreement(User currentUser, SignAgreementRequest request, String clientIp) {
        if (currentUser.getCompany() == null || currentUser.getCompany().getId() == null) {
            throw new AppException(ErrorCode.AGREEMENT_NOT_FOUND, "Người dùng chưa thuộc doanh nghiệp nào");
        }

        CompanyAgreement agreement = agreementRepository
                .findFirstByCompanyIdOrderByCreatedAtDesc(currentUser.getCompany().getId())
                .orElseThrow(() -> new AppException(ErrorCode.AGREEMENT_NOT_FOUND));

        if (agreement.getStatus() == AgreementStatus.SIGNED) {
            throw new AppException(ErrorCode.AGREEMENT_ALREADY_SIGNED);
        }

        LocalDateTime now = LocalDateTime.now();

        // Kiểm tra xem đã quá hạn 3 ngày chưa
        if (agreement.getDeadlineAt().isBefore(now)) {
            log.warn("User {} attempted to sign agreement {} past deadline {}", currentUser.getEmail(), agreement.getId(), agreement.getDeadlineAt());
            agreement.setStatus(AgreementStatus.EXPIRED);
            agreementRepository.save(agreement);

            currentUser.setStatus(UserStatus.LOCKED);
            userRepository.save(currentUser);

            throw new AppException(ErrorCode.AGREEMENT_DEADLINE_EXPIRED);
        }

        agreement.setStatus(AgreementStatus.SIGNED);
        agreement.setSignedAt(now);
        agreement.setUser(currentUser);
        if (request != null && request.signerName() != null && !request.signerName().isBlank()) {
            agreement.setSignerName(request.signerName().trim());
        } else if (currentUser.getFullName() != null) {
            agreement.setSignerName(currentUser.getFullName());
        }
        if (request != null && request.signerPhone() != null && !request.signerPhone().isBlank()) {
            agreement.setSignerPhone(request.signerPhone().trim());
        } else if (currentUser.getPhoneNumber() != null) {
            agreement.setSignerPhone(currentUser.getPhoneNumber());
        }
        agreement.setSignerEmail(currentUser.getEmail());
        agreement.setIpAddress(clientIp);

        CompanyAgreement saved = agreementRepository.save(agreement);
        log.info("Agreement {} signed successfully by user {} for company {}", saved.getId(), currentUser.getEmail(), currentUser.getCompany().getId());

        return toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public CompanyAgreementResponse getCompanyAgreement(Long companyId) {
        CompanyAgreement agreement = agreementRepository
                .findFirstByCompanyIdOrderByCreatedAtDesc(companyId)
                .orElseThrow(() -> new AppException(ErrorCode.AGREEMENT_NOT_FOUND));

        return toResponse(agreement);
    }

    private CompanyAgreementResponse toResponse(CompanyAgreement a) {
        LocalDateTime now = LocalDateTime.now();
        boolean isExpired = a.getStatus() == AgreementStatus.PENDING && a.getDeadlineAt().isBefore(now);
        long hoursRemaining = 0;
        if (a.getStatus() == AgreementStatus.PENDING && !isExpired) {
            hoursRemaining = Math.max(0, Duration.between(now, a.getDeadlineAt()).toHours());
        }

        return CompanyAgreementResponse.builder()
                .id(a.getId())
                .companyId(a.getCompany() != null ? a.getCompany().getId() : null)
                .companyName(a.getCompanyName())
                .companyCode(a.getCompanyCode())
                .taxCode(a.getTaxCode())
                .userId(a.getUser() != null ? a.getUser().getId() : null)
                .agreementType(a.getAgreementType())
                .agreementCode(a.getAgreementCode())
                .title(a.getTitle())
                .content(a.getContent())
                .status(isExpired ? AgreementStatus.EXPIRED : a.getStatus())
                .deadlineAt(a.getDeadlineAt())
                .signedAt(a.getSignedAt())
                .signerName(a.getSignerName())
                .signerEmail(a.getSignerEmail())
                .signerPhone(a.getSignerPhone())
                .ipAddress(a.getIpAddress())
                .isExpired(isExpired)
                .hoursRemaining(hoursRemaining)
                .createdAt(a.getCreatedAt())
                .build();
    }
}
