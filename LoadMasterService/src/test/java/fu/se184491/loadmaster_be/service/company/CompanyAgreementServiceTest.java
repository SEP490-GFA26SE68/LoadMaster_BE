package fu.se184491.loadmaster_be.service.company;

import fu.se184491.loadmaster_be.client.KeycloakAdminClient;
import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.account.UserStatus;
import fu.se184491.loadmaster_be.constant.company.AgreementStatus;
import fu.se184491.loadmaster_be.constant.company.AgreementType;
import fu.se184491.loadmaster_be.constant.company.CompanyStatus;
import fu.se184491.loadmaster_be.dto.request.company.SignAgreementRequest;
import fu.se184491.loadmaster_be.dto.response.company.CompanyAgreementResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.company.CompanyAgreement;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import fu.se184491.loadmaster_be.repository.company.CompanyAgreementRepository;
import fu.se184491.loadmaster_be.service.company.Impl.CompanyAgreementServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompanyAgreementServiceTest {

    @Mock
    private CompanyAgreementRepository agreementRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private KeycloakAdminClient keycloakAdminClient;

    @InjectMocks
    private CompanyAgreementServiceImpl agreementService;

    private Company testCompany;
    private User testAdminUser;
    private CompanyAgreement testAgreement;

    @BeforeEach
    void setUp() {
        testCompany = Company.builder()
                .id(100L)
                .companyCode("COMP-TEST")
                .companyName("Công ty Vận Tải Test")
                .taxCode("0123456789")
                .billingEmail("admin@testlogistics.vn")
                .status(CompanyStatus.ACTIVE)
                .createdAt(Instant.now())
                .build();

        testAdminUser = User.builder()
                .id(200L)
                .keycloakId("kc-test-admin")
                .company(testCompany)
                .email("admin@testlogistics.vn")
                .fullName("Nguyen Van A")
                .phoneNumber("0912345678")
                .userRoleType(UserRole.ADMIN)
                .status(UserStatus.ACTIVE)
                .createdAt(LocalDateTime.of(2026, 10, 1, 8, 0, 0))
                .updatedAt(LocalDateTime.of(2026, 10, 1, 8, 0, 0))
                .build();

        testAgreement = CompanyAgreement.builder()
                .id(500L)
                .company(testCompany)
                .user(testAdminUser)
                .agreementType(AgreementType.TRIAL_TERMS)
                .agreementCode("AGR-TRIAL-123456")
                .title("Thỏa thuận Sử dụng Dịch vụ Thử nghiệm LoadMaster")
                .content("Toàn văn điều khoản thỏa thuận...")
                .status(AgreementStatus.PENDING)
                .deadlineAt(LocalDateTime.now().plusDays(3))
                .companyName(testCompany.getCompanyName())
                .companyCode(testCompany.getCompanyCode())
                .taxCode(testCompany.getTaxCode())
                .signerName(testAdminUser.getFullName())
                .signerEmail(testAdminUser.getEmail())
                .signerPhone(testAdminUser.getPhoneNumber())
                .createdAt(Instant.now())
                .build();
    }

    @Test
    @DisplayName("Tạo thỏa thuận dùng thử thành công với hạn chót đúng 3 ngày kể từ lúc duyệt")
    void createTrialAgreement_shouldSetPendingAndDeadlineInThreeDays() {
        LocalDateTime approvedAt = LocalDateTime.of(2026, 10, 22, 10, 0, 0);
        when(agreementRepository.save(any(CompanyAgreement.class))).thenAnswer(inv -> inv.getArgument(0));

        CompanyAgreement created = agreementService.createTrialAgreement(testCompany, testAdminUser, approvedAt);

        assertThat(created).isNotNull();
        assertThat(created.getCompany()).isEqualTo(testCompany);
        assertThat(created.getUser()).isEqualTo(testAdminUser);
        assertThat(created.getStatus()).isEqualTo(AgreementStatus.PENDING);
        assertThat(created.getAgreementType()).isEqualTo(AgreementType.TRIAL_TERMS);
        assertThat(created.getDeadlineAt()).isEqualTo(approvedAt.plusDays(3));
        assertThat(created.getAgreementCode()).startsWith("AGR-");
        assertThat(created.getContent()).isNotEmpty();

        verify(agreementRepository).save(any(CompanyAgreement.class));
    }

    @Test
    @DisplayName("Lấy thỏa thuận của Company Admin thành công khi tồn tại thỏa thuận")
    void getMyAgreement_shouldReturnAgreementResponse() {
        when(agreementRepository.findFirstByCompanyIdOrderByCreatedAtDesc(100L))
                .thenReturn(Optional.of(testAgreement));

        CompanyAgreementResponse response = agreementService.getMyAgreement(testAdminUser);

        assertThat(response).isNotNull();
        assertThat(response.id()).isEqualTo(500L);
        assertThat(response.companyId()).isEqualTo(100L);
        assertThat(response.status()).isEqualTo(AgreementStatus.PENDING);
        assertThat(response.isExpired()).isFalse();
        assertThat(response.hoursRemaining()).isGreaterThan(0);
    }

    @Test
    @DisplayName("Lấy thỏa thuận thất bại khi công ty chưa có thỏa thuận nào")
    void getMyAgreement_shouldThrowNotFoundWhenEmpty() {
        when(agreementRepository.findFirstByCompanyIdOrderByCreatedAtDesc(100L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> agreementService.getMyAgreement(testAdminUser))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.AGREEMENT_NOT_FOUND);
    }

    @Test
    @DisplayName("Company Admin ký thỏa thuận thành công khi còn trong hạn 3 ngày")
    void signAgreement_shouldUpdateStatusToSignedAndSaveSignerInfo() {
        when(agreementRepository.findFirstByCompanyIdOrderByCreatedAtDesc(100L))
                .thenReturn(Optional.of(testAgreement));
        when(agreementRepository.save(any(CompanyAgreement.class))).thenAnswer(inv -> inv.getArgument(0));

        SignAgreementRequest request = SignAgreementRequest.builder()
                .confirmTerms(true)
                .signerName("Nguyen Van A - Dai dien")
                .signerPhone("0988888888")
                .signerEmail("custom-signer@gmail.com")
                .build();

        CompanyAgreementResponse response = agreementService.signAgreement(testAdminUser, request, "192.168.1.1");

        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(AgreementStatus.SIGNED);
        assertThat(response.signedAt()).isNotNull();
        assertThat(response.signerName()).isEqualTo("Nguyen Van A - Dai dien");
        assertThat(response.signerPhone()).isEqualTo("0988888888");
        assertThat(response.signerEmail()).isEqualTo("custom-signer@gmail.com");
        assertThat(response.ipAddress()).isEqualTo("192.168.1.1");

        ArgumentCaptor<CompanyAgreement> captor = ArgumentCaptor.forClass(CompanyAgreement.class);
        verify(agreementRepository).save(captor.capture());
        CompanyAgreement saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(AgreementStatus.SIGNED);
        assertThat(saved.getSignedAt()).isNotNull();
    }

    @Test
    @DisplayName("Ký thỏa thuận thất bại nếu thỏa thuận đã được ký trước đó")
    void signAgreement_shouldThrowIfAlreadySigned() {
        testAgreement.setStatus(AgreementStatus.SIGNED);
        testAgreement.setSignedAt(LocalDateTime.now().minusDays(1));
        when(agreementRepository.findFirstByCompanyIdOrderByCreatedAtDesc(100L))
                .thenReturn(Optional.of(testAgreement));

        SignAgreementRequest request = SignAgreementRequest.builder()
                .confirmTerms(true)
                .build();

        assertThatThrownBy(() -> agreementService.signAgreement(testAdminUser, request, "127.0.0.1"))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.AGREEMENT_ALREADY_SIGNED);

        verify(agreementRepository, never()).save(any());
    }

    @Test
    @DisplayName("User bị FLAGGED (quá hạn 72h) vẫn ký được thỏa thuận trong vòng 30 ngày và tự mở khóa phục hồi về ACTIVE")
    void signAgreement_shouldRecoverFlaggedUserWhenSigningWithin30Days() {
        testAdminUser.setStatus(UserStatus.FLAGGED);
        testAgreement.setStatus(AgreementStatus.EXPIRED);
        testAgreement.setDeadlineAt(LocalDateTime.now().minusDays(5)); // Quá hạn 72h (đã 5 ngày), nhưng trong hạn 30 ngày
        when(agreementRepository.findFirstByCompanyIdOrderByCreatedAtDesc(100L))
                .thenReturn(Optional.of(testAgreement));
        when(agreementRepository.save(any(CompanyAgreement.class))).thenAnswer(inv -> inv.getArgument(0));

        SignAgreementRequest request = SignAgreementRequest.builder()
                .confirmTerms(true)
                .signerName("Nguyen Van A - Recover")
                .signerPhone("0912345678")
                .build();

        CompanyAgreementResponse response = agreementService.signAgreement(testAdminUser, request, "127.0.0.1");

        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(AgreementStatus.SIGNED);
        assertThat(testAdminUser.getStatus()).isEqualTo(UserStatus.ACTIVE);
        verify(userRepository).save(testAdminUser);
        verify(agreementRepository).save(testAgreement);
    }

    @Test
    @DisplayName("Ký thỏa thuận thất bại khi đã quá hạn 30 ngày kể từ deadline")
    void signAgreement_shouldThrowIfPast30Days() {
        testAgreement.setDeadlineAt(LocalDateTime.now().minusDays(32)); // Quá hạn 32 ngày (>30 ngày)
        when(agreementRepository.findFirstByCompanyIdOrderByCreatedAtDesc(100L))
                .thenReturn(Optional.of(testAgreement));

        SignAgreementRequest request = SignAgreementRequest.builder()
                .confirmTerms(true)
                .build();

        assertThatThrownBy(() -> agreementService.signAgreement(testAdminUser, request, "127.0.0.1"))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.AGREEMENT_DEADLINE_EXPIRED);

        verify(userRepository).save(testAdminUser);
        assertThat(testAdminUser.getStatus()).isEqualTo(UserStatus.FLAGGED);
        verify(agreementRepository).save(testAgreement);
        assertThat(testAgreement.getStatus()).isEqualTo(AgreementStatus.EXPIRED);
    }

    @Test
    @DisplayName("Seam 3 - Quá 72 giờ chưa ký: gắn cờ (FLAGGED) tài khoản dùng thử; sau 30 ngày xóa vĩnh viễn khỏi Keycloak và DB")
    void processExpiredTrialAgreements_flagsAfter72hAndHardDeletesAfter30Days() {
        // 1. Thỏa thuận PENDING đã quá hạn 72h (nhưng chưa tới 30 ngày) -> Gắn cờ FLAGGED
        User flaggedUser = User.builder()
                .id(301L)
                .keycloakId("kc-flagged-301")
                .company(testCompany)
                .email("trial-72h@test.vn")
                .userRoleType(UserRole.ADMIN)
                .status(UserStatus.ACTIVE)
                .createdAt(LocalDateTime.now().minusDays(4).withNano(0))
                .updatedAt(LocalDateTime.now().minusDays(4).withNano(0))
                .build();

        CompanyAgreement pendingPast72h = CompanyAgreement.builder()
                .id(601L)
                .company(testCompany)
                .user(flaggedUser)
                .status(AgreementStatus.PENDING)
                .deadlineAt(LocalDateTime.now().minusHours(5))
                .build();

        // 2. Thỏa thuận EXPIRED đã quá 30 ngày kể từ deadline -> Xóa vĩnh viễn (Hard Delete)
        User hardDeleteUser = User.builder()
                .id(302L)
                .keycloakId("kc-hard-302")
                .company(testCompany)
                .email("trial-30days@test.vn")
                .userRoleType(UserRole.ADMIN)
                .status(UserStatus.FLAGGED)
                .createdAt(LocalDateTime.now().minusDays(40).withNano(0))
                .updatedAt(LocalDateTime.now().minusDays(31).withNano(0))
                .build();

        CompanyAgreement expiredPast30Days = CompanyAgreement.builder()
                .id(602L)
                .company(testCompany)
                .user(hardDeleteUser)
                .status(AgreementStatus.EXPIRED)
                .deadlineAt(LocalDateTime.now().minusDays(31))
                .build();

        when(agreementRepository.findByStatusAndDeadlineAtBefore(eq(AgreementStatus.PENDING), any(LocalDateTime.class)))
                .thenReturn(List.of(pendingPast72h));
        when(agreementRepository.findByStatusAndDeadlineAtBefore(eq(AgreementStatus.EXPIRED), any(LocalDateTime.class)))
                .thenReturn(List.of(expiredPast30Days));

        agreementService.processExpiredTrialAgreements();

        // Kiểm tra bước 1: Gắn cờ sau 72h không ký
        assertThat(pendingPast72h.getStatus()).isEqualTo(AgreementStatus.EXPIRED);
        assertThat(flaggedUser.getStatus()).isEqualTo(UserStatus.FLAGGED);
        assertThat(flaggedUser.getUpdatedAt()).isAfterOrEqualTo(flaggedUser.getCreatedAt());
        verify(userRepository).save(flaggedUser);
        verify(agreementRepository).save(pendingPast72h);
        verify(keycloakAdminClient, never()).deleteUser("kc-flagged-301");
        verify(userRepository, never()).delete(flaggedUser);

        // Kiểm tra bước 2: Xóa vĩnh viễn sau 30 ngày
        verify(keycloakAdminClient).deleteUser("kc-hard-302");
        assertThat(expiredPast30Days.getUser()).isNull();
        verify(agreementRepository).save(expiredPast30Days);
        verify(userRepository).delete(hardDeleteUser);
    }
}
