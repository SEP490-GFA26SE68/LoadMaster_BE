package fu.se184491.loadmaster_be.service.company;

import fu.se184491.loadmaster_be.client.KeycloakAdminClient;
import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.account.UserStatus;
import fu.se184491.loadmaster_be.constant.company.AgreementStatus;
import fu.se184491.loadmaster_be.constant.company.AgreementType;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.company.CompanyAgreement;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import fu.se184491.loadmaster_be.repository.company.CompanyAgreementRepository;
import fu.se184491.loadmaster_be.service.company.TrialAgreementEnforcementJob;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TrialAgreementEnforcementJobTest {

    @Mock
    private CompanyAgreementRepository agreementRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private KeycloakAdminClient keycloakAdminClient;

    @InjectMocks
    private TrialAgreementEnforcementJob enforcementJob;

    @Test
    @DisplayName("Quét và khóa tài khoản Company Admin khi thỏa thuận quá hạn 3 ngày chưa ký")
    void enforceUnsignedAgreements_shouldLockOverdueAccounts() {
        Company company = Company.builder().id(10L).companyCode("COMP-10").build();
        User admin = User.builder()
                .id(20L)
                .keycloakId("kc-admin-20")
                .company(company)
                .email("admin@comp10.vn")
                .userRoleType(UserRole.ADMIN)
                .status(UserStatus.ACTIVE)
                .build();

        CompanyAgreement overdueAgreement = CompanyAgreement.builder()
                .id(100L)
                .company(company)
                .user(admin)
                .status(AgreementStatus.PENDING)
                .deadlineAt(LocalDateTime.now().minusHours(1)) // Quá hạn 1 giờ
                .build();

        when(agreementRepository.findByStatusAndDeadlineAtBefore(eq(AgreementStatus.PENDING), any(LocalDateTime.class)))
                .thenReturn(List.of(overdueAgreement));

        int lockedCount = enforcementJob.enforceUnsignedAgreements();

        assertThat(lockedCount).isEqualTo(1);
        assertThat(overdueAgreement.getStatus()).isEqualTo(AgreementStatus.EXPIRED);
        assertThat(admin.getStatus()).isEqualTo(UserStatus.LOCKED);

        verify(agreementRepository).save(overdueAgreement);
        verify(userRepository).save(admin);
        verify(keycloakAdminClient).setUserEnabled("kc-admin-20", false);
    }

    @Test
    @DisplayName("Không khóa tài khoản khi không có thỏa thuận nào quá hạn")
    void enforceUnsignedAgreements_shouldDoNothingWhenNoOverdue() {
        when(agreementRepository.findByStatusAndDeadlineAtBefore(eq(AgreementStatus.PENDING), any(LocalDateTime.class)))
                .thenReturn(Collections.emptyList());

        int lockedCount = enforcementJob.enforceUnsignedAgreements();

        assertThat(lockedCount).isEqualTo(0);
        verify(userRepository, never()).save(any());
        verify(keycloakAdminClient, never()).setUserEnabled(anyString(), anyBoolean());
    }
}
