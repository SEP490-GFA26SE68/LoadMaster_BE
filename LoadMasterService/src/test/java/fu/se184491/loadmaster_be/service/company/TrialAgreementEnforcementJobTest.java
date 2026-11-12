package fu.se184491.loadmaster_be.service.company;

import fu.se184491.loadmaster_be.client.KeycloakAdminClient;
import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.account.UserStatus;
import fu.se184491.loadmaster_be.constant.account.UserType;
import fu.se184491.loadmaster_be.constant.company.AgreementStatus;
import fu.se184491.loadmaster_be.constant.company.AgreementType;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.company.CompanyAgreement;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import fu.se184491.loadmaster_be.repository.company.CompanyAgreementRepository;
import fu.se184491.loadmaster_be.repository.company.CompanyRepository;
import fu.se184491.loadmaster_be.repository.support.SupportTicketRepository;
import fu.se184491.loadmaster_be.repository.support.TicketReplyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TrialAgreementEnforcementJobTest {

    @Mock
    private CompanyAgreementRepository agreementRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private CompanyRepository companyRepository;

    @Mock
    private SupportTicketRepository supportTicketRepository;

    @Mock
    private TicketReplyRepository ticketReplyRepository;

    @Mock
    private KeycloakAdminClient keycloakAdminClient;

    // Transaction manager giả: TransactionTemplate gọi getTransaction → callback → commit (hoặc rollback khi lỗi),
    // nên test kiểm được thứ tự "DB commit trước, Keycloak sau".
    @Mock
    private PlatformTransactionManager transactionManager;

    private TrialAgreementEnforcementJob enforcementJob;
    private Company company;

    @BeforeEach
    void setUp() {
        enforcementJob = new TrialAgreementEnforcementJob(
                agreementRepository, userRepository, companyRepository, supportTicketRepository, ticketReplyRepository,
                keycloakAdminClient, transactionManager, true);
        company = Company.builder().id(10L).companyCode("COMP-10").build();
    }

    // ── Gắn cờ ───────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Thỏa thuận quá hạn 72 giờ: EXPIRED kèm expiredAt, tài khoản bị gắn cờ, Keycloak không bị đụng tới")
    void flagsOverdueAgreementAndRecordsWhenItExpired() {
        User trialAdmin = user(20L, "kc-20", UserType.TRIAL, UserStatus.ACTIVE);
        CompanyAgreement agreement = pending(100L, trialAdmin);
        when(agreementRepository.findIdsByStatusAndDeadlineAtBefore(eq(AgreementStatus.PENDING), any()))
                .thenReturn(List.of(100L));
        when(agreementRepository.findById(100L)).thenReturn(Optional.of(agreement));

        LocalDateTime before = LocalDateTime.now();
        int flagged = enforcementJob.enforceUnsignedAgreements();

        assertThat(flagged).isEqualTo(1);
        assertThat(agreement.getStatus()).isEqualTo(AgreementStatus.EXPIRED);
        assertThat(agreement.getExpiredAt()).isBetween(before, LocalDateTime.now());
        assertThat(trialAdmin.getStatus()).isEqualTo(UserStatus.FLAGGED);
        verify(agreementRepository).save(agreement);
        verify(userRepository).save(trialAdmin);
        verify(userRepository, never()).delete(any(User.class));
        verify(keycloakAdminClient, never()).setUserEnabled(anyString(), anyBoolean());
        verify(keycloakAdminClient, never()).deleteUser(anyString());
    }

    @Test
    @DisplayName("Thỏa thuận không gắn người: gắn cờ Quản trị công ty, kể cả tài khoản OFFICIAL")
    void flagsCompanyAdminWhenAgreementHasNoUser() {
        User dispatcher = user(19L, "kc-19", UserType.OFFICIAL, UserStatus.ACTIVE);
        dispatcher.setUserRoleType(UserRole.DISPATCHER);
        User officialAdmin = user(21L, "kc-21", UserType.OFFICIAL, UserStatus.ACTIVE);
        CompanyAgreement agreement = pending(101L, null);
        when(agreementRepository.findIdsByStatusAndDeadlineAtBefore(eq(AgreementStatus.PENDING), any()))
                .thenReturn(List.of(101L));
        when(agreementRepository.findById(101L)).thenReturn(Optional.of(agreement));
        when(userRepository.findByCompanyId(10L)).thenReturn(List.of(dispatcher, officialAdmin));

        assertThat(enforcementJob.enforceUnsignedAgreements()).isEqualTo(1);

        assertThat(officialAdmin.getStatus()).isEqualTo(UserStatus.FLAGGED);
        assertThat(dispatcher.getStatus()).isEqualTo(UserStatus.ACTIVE);
        verify(userRepository).save(officialAdmin);
    }

    @Test
    @DisplayName("Thỏa thuận đã được ký (hoặc gia hạn) giữa lúc lấy danh sách và lúc xử lý thì không bị đụng tới")
    void skipsAgreementSignedSinceTheListWasRead() {
        User trialAdmin = user(20L, "kc-20", UserType.TRIAL, UserStatus.ACTIVE);
        CompanyAgreement agreement = pending(100L, trialAdmin);
        agreement.setStatus(AgreementStatus.SIGNED);
        when(agreementRepository.findIdsByStatusAndDeadlineAtBefore(eq(AgreementStatus.PENDING), any()))
                .thenReturn(List.of(100L));
        when(agreementRepository.findById(100L)).thenReturn(Optional.of(agreement));

        assertThat(enforcementJob.enforceUnsignedAgreements()).isZero();

        assertThat(trialAdmin.getStatus()).isEqualTo(UserStatus.ACTIVE);
        verify(agreementRepository, never()).save(any());
    }

    @Test
    @DisplayName("Một thỏa thuận lỗi khi gắn cờ không chặn các thỏa thuận khác")
    void flagFailureOnOneAgreementDoesNotStopTheOthers() {
        User broken = user(20L, "kc-20", UserType.TRIAL, UserStatus.ACTIVE);
        User healthy = user(22L, "kc-22", UserType.TRIAL, UserStatus.ACTIVE);
        CompanyAgreement brokenAgreement = pending(100L, broken);
        CompanyAgreement healthyAgreement = pending(102L, healthy);
        when(agreementRepository.findIdsByStatusAndDeadlineAtBefore(eq(AgreementStatus.PENDING), any()))
                .thenReturn(List.of(100L, 102L));
        when(agreementRepository.findById(100L)).thenReturn(Optional.of(brokenAgreement));
        when(agreementRepository.findById(102L)).thenReturn(Optional.of(healthyAgreement));
        when(userRepository.save(broken)).thenThrow(new DataIntegrityViolationException("boom"));

        int flagged = enforcementJob.enforceUnsignedAgreements();

        assertThat(flagged).isEqualTo(1);
        assertThat(healthy.getStatus()).isEqualTo(UserStatus.FLAGGED);
        verify(userRepository).save(healthy);
        verify(transactionManager, atLeastOnce()).rollback(any());
    }

    // ── Bản ghi cũ chưa có expiredAt ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Thỏa thuận EXPIRED cũ chưa có expiredAt được điền mốc lúc chạy, nên không bị xóa theo hạn 72 giờ cũ")
    void backfillsMissingExpiredAtAndDoesNotDeleteLegacyRows() {
        when(agreementRepository.fillMissingExpiredAt(eq(AgreementStatus.EXPIRED), any())).thenReturn(2);

        LocalDateTime before = LocalDateTime.now();
        enforcementJob.enforceUnsignedAgreements();

        ArgumentCaptor<LocalDateTime> filledWith = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(agreementRepository).fillMissingExpiredAt(eq(AgreementStatus.EXPIRED), filledWith.capture());
        assertThat(filledWith.getValue()).isBetween(before, LocalDateTime.now());

        // Bước xóa chỉ tìm theo expiredAt, mốc là 30 ngày trước lần chạy.
        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(agreementRepository).findIdsByStatusAndExpiredAtBefore(eq(AgreementStatus.EXPIRED), cutoff.capture());
        assertThat(cutoff.getValue()).isEqualTo(filledWith.getValue().minusDays(30));
        verify(userRepository, never()).delete(any(User.class));
        verify(keycloakAdminClient, never()).deleteUser(anyString());
    }

    @Test
    @DisplayName("Tắt app.trial.hard-delete-enabled (mặc định): vẫn gắn cờ, không tìm và không xóa tài khoản nào")
    void hardDeleteIsSkippedWhenDisabled() {
        TrialAgreementEnforcementJob disabled = new TrialAgreementEnforcementJob(
                agreementRepository, userRepository, companyRepository, supportTicketRepository, ticketReplyRepository,
                keycloakAdminClient, transactionManager, false);
        User trialAdmin = user(20L, "kc-20", UserType.TRIAL, UserStatus.ACTIVE);
        when(agreementRepository.findIdsByStatusAndDeadlineAtBefore(eq(AgreementStatus.PENDING), any()))
                .thenReturn(List.of(100L));
        when(agreementRepository.findById(100L)).thenReturn(Optional.of(pending(100L, trialAdmin)));

        assertThat(disabled.enforceUnsignedAgreements()).isEqualTo(1);

        assertThat(trialAdmin.getStatus()).isEqualTo(UserStatus.FLAGGED);
        verify(agreementRepository, never()).findIdsByStatusAndExpiredAtBefore(any(), any());
        verify(userRepository, never()).delete(any(User.class));
        verify(keycloakAdminClient, never()).deleteUser(anyString());
    }

    // ── Xóa cứng ─────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Tài khoản TRIAL bị gắn cờ quá 30 ngày kể từ expiredAt: xóa DB, commit, rồi mới xóa Keycloak; công ty dùng thử trống thì xóa")
    void deletesTrialAccountFromDbBeforeKeycloak() {
        User trialAdmin = user(20L, "kc-20", UserType.TRIAL, UserStatus.FLAGGED);
        CompanyAgreement agreement = expired(200L, trialAdmin, LocalDateTime.now().minusDays(31));
        when(agreementRepository.findIdsByStatusAndExpiredAtBefore(eq(AgreementStatus.EXPIRED), any()))
                .thenReturn(List.of(200L));
        when(agreementRepository.findById(200L)).thenReturn(Optional.of(agreement));
        when(userRepository.findByCompanyId(10L)).thenReturn(List.of());

        enforcementJob.enforceUnsignedAgreements();

        InOrder order = inOrder(agreementRepository, userRepository, transactionManager, keycloakAdminClient, companyRepository);
        order.verify(agreementRepository).delete(agreement);
        order.verify(userRepository).delete(trialAdmin);
        order.verify(transactionManager).commit(any());
        order.verify(keycloakAdminClient).deleteUser("kc-20");
        order.verify(companyRepository).deleteById(10L);
        order.verify(transactionManager).commit(any());
    }

    @Test
    @DisplayName("Xóa cứng tài khoản TRIAL: trả lời của các yêu cầu hỗ trợ xóa trước, rồi phiếu, rồi tài khoản — cùng một transaction")
    void deletesTheTrialAccountsTicketRepliesBeforeTheTicketsAndTheAccount() {
        User trialAdmin = user(20L, "kc-20", UserType.TRIAL, UserStatus.FLAGGED);
        CompanyAgreement agreement = expired(200L, trialAdmin, LocalDateTime.now().minusDays(31));
        when(agreementRepository.findIdsByStatusAndExpiredAtBefore(eq(AgreementStatus.EXPIRED), any()))
                .thenReturn(List.of(200L));
        when(agreementRepository.findById(200L)).thenReturn(Optional.of(agreement));
        when(supportTicketRepository.findIdsByRequesterId(20L)).thenReturn(List.of(7L, 8L));
        when(userRepository.findByCompanyId(10L)).thenReturn(List.of());

        enforcementJob.enforceUnsignedAgreements();

        InOrder order = inOrder(ticketReplyRepository, supportTicketRepository, userRepository, transactionManager);
        order.verify(ticketReplyRepository).deleteByTicketIdIn(List.of(7L, 8L));
        order.verify(supportTicketRepository).deleteAllByIdInBatch(List.of(7L, 8L));
        order.verify(userRepository).delete(trialAdmin);
        order.verify(transactionManager).commit(any());
    }

    @Test
    @DisplayName("Tài khoản OFFICIAL chỉ bị gắn cờ: không bao giờ bị xóa, công ty của họ cũng vậy")
    void neverDeletesOfficialAccountsOrTheirCompany() {
        User officialAdmin = user(21L, "kc-21", UserType.OFFICIAL, UserStatus.FLAGGED);
        CompanyAgreement agreement = expired(201L, officialAdmin, LocalDateTime.now().minusDays(90));
        when(agreementRepository.findIdsByStatusAndExpiredAtBefore(eq(AgreementStatus.EXPIRED), any()))
                .thenReturn(List.of(201L));
        when(agreementRepository.findById(201L)).thenReturn(Optional.of(agreement));

        enforcementJob.enforceUnsignedAgreements();

        verify(userRepository, never()).delete(any(User.class));
        verify(agreementRepository, never()).delete(any());
        verify(companyRepository, never()).deleteById(any());
        verify(keycloakAdminClient, never()).deleteUser(anyString());
    }

    @Test
    @DisplayName("OFFICIAL tìm qua Quản trị công ty (thỏa thuận không gắn người) cũng không bị xóa")
    void neverDeletesOfficialCompanyAdminFoundByFallback() {
        User officialAdmin = user(21L, "kc-21", UserType.OFFICIAL, UserStatus.FLAGGED);
        CompanyAgreement agreement = expired(202L, null, LocalDateTime.now().minusDays(45));
        when(agreementRepository.findIdsByStatusAndExpiredAtBefore(eq(AgreementStatus.EXPIRED), any()))
                .thenReturn(List.of(202L));
        when(agreementRepository.findById(202L)).thenReturn(Optional.of(agreement));
        when(userRepository.findByCompanyId(10L)).thenReturn(List.of(officialAdmin));

        enforcementJob.enforceUnsignedAgreements();

        verify(userRepository, never()).delete(any(User.class));
        verify(keycloakAdminClient, never()).deleteUser(anyString());
    }

    @Test
    @DisplayName("Chưa đủ 30 ngày kể từ expiredAt thì không xóa, dù hạn 72 giờ đã qua từ lâu")
    void measuresThirtyDaysFromExpiredAtNotFromDeadline() {
        User trialAdmin = user(20L, "kc-20", UserType.TRIAL, UserStatus.FLAGGED);
        CompanyAgreement agreement = expired(203L, trialAdmin, LocalDateTime.now().minusDays(29));
        agreement.setDeadlineAt(LocalDateTime.now().minusDays(60));
        when(agreementRepository.findIdsByStatusAndExpiredAtBefore(eq(AgreementStatus.EXPIRED), any()))
                .thenReturn(List.of(203L));
        when(agreementRepository.findById(203L)).thenReturn(Optional.of(agreement));

        enforcementJob.enforceUnsignedAgreements();

        verify(userRepository, never()).delete(any(User.class));
        verify(keycloakAdminClient, never()).deleteUser(anyString());
    }

    @Test
    @DisplayName("Tài khoản TRIAL đã được hỗ trợ mở khóa (không còn FLAGGED) thì không bị xóa")
    void keepsTrialAccountThatWasUnlocked() {
        User trialAdmin = user(20L, "kc-20", UserType.TRIAL, UserStatus.ACTIVE);
        CompanyAgreement agreement = expired(204L, trialAdmin, LocalDateTime.now().minusDays(40));
        when(agreementRepository.findIdsByStatusAndExpiredAtBefore(eq(AgreementStatus.EXPIRED), any()))
                .thenReturn(List.of(204L));
        when(agreementRepository.findById(204L)).thenReturn(Optional.of(agreement));

        enforcementJob.enforceUnsignedAgreements();

        verify(userRepository, never()).delete(any(User.class));
        verify(keycloakAdminClient, never()).deleteUser(anyString());
    }

    @Test
    @DisplayName("DB xóa lỗi thì không gọi Keycloak cho tài khoản đó, và các thỏa thuận khác vẫn được xử lý")
    void dbFailureSkipsKeycloakAndDoesNotStopOtherAgreements() {
        User blocked = user(20L, "kc-blocked", UserType.TRIAL, UserStatus.FLAGGED);
        User deletable = user(23L, "kc-ok", UserType.TRIAL, UserStatus.FLAGGED);
        CompanyAgreement blockedAgreement = expired(205L, blocked, LocalDateTime.now().minusDays(35));
        CompanyAgreement okAgreement = expired(206L, deletable, LocalDateTime.now().minusDays(35));
        when(agreementRepository.findIdsByStatusAndExpiredAtBefore(eq(AgreementStatus.EXPIRED), any()))
                .thenReturn(List.of(205L, 206L));
        when(agreementRepository.findById(205L)).thenReturn(Optional.of(blockedAgreement));
        when(agreementRepository.findById(206L)).thenReturn(Optional.of(okAgreement));
        doThrow(new DataIntegrityViolationException("user still referenced"))
                .when(userRepository).delete(blocked);
        when(userRepository.findByCompanyId(10L)).thenReturn(List.of(blocked));

        assertThatCode(() -> enforcementJob.enforceUnsignedAgreements()).doesNotThrowAnyException();

        verify(keycloakAdminClient, never()).deleteUser("kc-blocked");
        verify(keycloakAdminClient).deleteUser("kc-ok");
        verify(transactionManager).rollback(any());
        // Công ty vẫn còn người (tài khoản không xóa được) nên giữ lại.
        verify(companyRepository, never()).deleteById(any());
    }

    @Test
    @DisplayName("Keycloak lỗi sau khi DB đã xóa: ghi log, không ném, công ty dùng thử trống vẫn được xóa")
    void keycloakFailureIsLoggedNotThrown() {
        User trialAdmin = user(20L, "kc-20", UserType.TRIAL, UserStatus.FLAGGED);
        CompanyAgreement agreement = expired(207L, trialAdmin, LocalDateTime.now().minusDays(31));
        when(agreementRepository.findIdsByStatusAndExpiredAtBefore(eq(AgreementStatus.EXPIRED), any()))
                .thenReturn(List.of(207L));
        when(agreementRepository.findById(207L)).thenReturn(Optional.of(agreement));
        doThrow(new IllegalStateException("Keycloak down")).when(keycloakAdminClient).deleteUser("kc-20");
        when(userRepository.findByCompanyId(10L)).thenReturn(List.of());

        assertThatCode(() -> enforcementJob.enforceUnsignedAgreements()).doesNotThrowAnyException();

        verify(userRepository).delete(trialAdmin);
        verify(companyRepository).deleteById(10L);
    }

    @Test
    @DisplayName("Công ty không xóa được vì khóa ngoại: ghi log và bỏ qua, tài khoản đã xóa vẫn giữ nguyên kết quả")
    void companyDeleteBlockedByForeignKeysIsSkipped() {
        User trialAdmin = user(20L, "kc-20", UserType.TRIAL, UserStatus.FLAGGED);
        CompanyAgreement agreement = expired(208L, trialAdmin, LocalDateTime.now().minusDays(31));
        when(agreementRepository.findIdsByStatusAndExpiredAtBefore(eq(AgreementStatus.EXPIRED), any()))
                .thenReturn(List.of(208L));
        when(agreementRepository.findById(208L)).thenReturn(Optional.of(agreement));
        when(userRepository.findByCompanyId(10L)).thenReturn(List.of());
        doThrow(new DataIntegrityViolationException("company still referenced"))
                .when(companyRepository).deleteById(10L);

        assertThatCode(() -> enforcementJob.enforceUnsignedAgreements()).doesNotThrowAnyException();

        verify(userRepository).delete(trialAdmin);
        verify(keycloakAdminClient).deleteUser("kc-20");
    }

    @Test
    @DisplayName("Công ty còn người dùng khác thì không bị xóa")
    void keepsCompanyThatStillHasUsers() {
        User trialAdmin = user(20L, "kc-20", UserType.TRIAL, UserStatus.FLAGGED);
        User colleague = user(24L, "kc-24", UserType.TRIAL, UserStatus.ACTIVE);
        colleague.setUserRoleType(UserRole.DISPATCHER);
        CompanyAgreement agreement = expired(209L, trialAdmin, LocalDateTime.now().minusDays(31));
        when(agreementRepository.findIdsByStatusAndExpiredAtBefore(eq(AgreementStatus.EXPIRED), any()))
                .thenReturn(List.of(209L));
        when(agreementRepository.findById(209L)).thenReturn(Optional.of(agreement));
        when(userRepository.findByCompanyId(10L)).thenReturn(List.of(colleague));

        enforcementJob.enforceUnsignedAgreements();

        verify(userRepository).delete(trialAdmin);
        verify(companyRepository, never()).deleteById(any());
    }

    @Test
    @DisplayName("Không có thỏa thuận nào quá hạn thì không làm gì")
    void doesNothingWhenNothingIsOverdue() {
        assertThat(enforcementJob.enforceUnsignedAgreements()).isZero();

        verify(userRepository, never()).save(any());
        verify(userRepository, never()).delete(any(User.class));
        verify(keycloakAdminClient, never()).deleteUser(anyString());
    }

    // ── Dữ liệu ──────────────────────────────────────────────────────────────────────────────────────────────────

    private User user(Long id, String keycloakId, UserType type, UserStatus status) {
        return User.builder()
                .id(id)
                .keycloakId(keycloakId)
                .company(company)
                .email("user" + id + "@comp10.vn")
                .userRoleType(UserRole.ADMIN)
                .userType(type)
                .status(status)
                .build();
    }

    private CompanyAgreement pending(Long id, User user) {
        return CompanyAgreement.builder()
                .id(id)
                .company(company)
                .user(user)
                .agreementType(AgreementType.TRIAL_TERMS)
                .status(AgreementStatus.PENDING)
                .deadlineAt(LocalDateTime.now().minusHours(1))
                .build();
    }

    private CompanyAgreement expired(Long id, User user, LocalDateTime expiredAt) {
        return CompanyAgreement.builder()
                .id(id)
                .company(company)
                .user(user)
                .agreementType(AgreementType.TRIAL_TERMS)
                .status(AgreementStatus.EXPIRED)
                .deadlineAt(expiredAt.minusHours(1))
                .expiredAt(expiredAt)
                .build();
    }
}
