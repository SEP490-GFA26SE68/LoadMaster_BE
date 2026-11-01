package fu.se184491.loadmaster_be.service.company;

import fu.se184491.loadmaster_be.client.KeycloakAdminClient;
import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.account.UserStatus;
import fu.se184491.loadmaster_be.constant.company.AgreementStatus;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.company.CompanyAgreement;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import fu.se184491.loadmaster_be.repository.company.CompanyAgreementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class TrialAgreementEnforcementJob {

    private final CompanyAgreementRepository agreementRepository;
    private final UserRepository userRepository;
    private final KeycloakAdminClient keycloakAdminClient;

    /**
     * Chạy định kỳ mỗi 30 phút để quét các thỏa thuận dùng thử đã quá hạn 3 ngày chưa ký.
     * Tự động khóa tài khoản Company Admin trong DB và vô hiệu hóa trên Keycloak.
     */
    @Scheduled(cron = "0 0/30 * * * ?")
    @Transactional
    public int enforceUnsignedAgreements() {
        LocalDateTime now = LocalDateTime.now();
        List<CompanyAgreement> overdueAgreements = agreementRepository
                .findByStatusAndDeadlineAtBefore(AgreementStatus.PENDING, now);

        if (overdueAgreements.isEmpty()) {
            return 0;
        }

        log.info("Found {} overdue trial agreements exceeding 3-day deadline. Enforcing account lock...", overdueAgreements.size());
        int lockedCount = 0;

        for (CompanyAgreement agreement : overdueAgreements) {
            try {
                agreement.setStatus(AgreementStatus.EXPIRED);
                agreementRepository.save(agreement);

                User targetUser = agreement.getUser();
                if (targetUser == null && agreement.getCompany() != null) {
                    targetUser = userRepository.findByCompanyId(agreement.getCompany().getId()).stream()
                            .filter(u -> u.getUserRoleType() == UserRole.ADMIN)
                            .findFirst()
                            .orElse(null);
                }

                if (targetUser != null && targetUser.getStatus() != UserStatus.LOCKED) {
                    targetUser.setStatus(UserStatus.LOCKED);
                    userRepository.save(targetUser);

                    if (targetUser.getKeycloakId() != null && !targetUser.getKeycloakId().isBlank()) {
                        try {
                            keycloakAdminClient.setUserEnabled(targetUser.getKeycloakId(), false);
                        } catch (Exception kcEx) {
                            log.error("Failed to disable Keycloak user {} during trial agreement enforcement: {}", targetUser.getKeycloakId(), kcEx.getMessage());
                        }
                    }

                    log.warn("LOCKED Company Admin user {} (id={}) of company {} due to unsigned agreement past 3-day deadline",
                            targetUser.getEmail(), targetUser.getId(), agreement.getCompany() != null ? agreement.getCompany().getId() : "null");
                    lockedCount++;
                }
            } catch (Exception ex) {
                log.error("Error enforcing trial agreement lock for agreement id {}: {}", agreement.getId(), ex.getMessage(), ex);
            }
        }

        return lockedCount;
    }
}
