package fu.se184491.loadmaster_be.service.company;

import fu.se184491.loadmaster_be.client.KeycloakAdminClient;
import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.account.UserStatus;
import fu.se184491.loadmaster_be.constant.company.AgreementStatus;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.company.CompanyAgreement;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import fu.se184491.loadmaster_be.repository.company.CompanyAgreementRepository;
import fu.se184491.loadmaster_be.repository.company.CompanyRepository;
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
    private final CompanyRepository companyRepository;
    private final KeycloakAdminClient keycloakAdminClient;

    /**
     * Chạy định kỳ mỗi 30 phút để:
     * 1. Quét các thỏa thuận dùng thử đã quá hạn 72 giờ chưa ký -> Gắn cờ tài khoản (FLAGGED), vẫn cho phép đăng nhập.
     * 2. Quét các tài khoản đã bị gắn cờ quá 30 ngày -> Xóa vĩnh viễn khỏi Keycloak và DB.
     */
    @Scheduled(cron = "0 0/30 * * * ?")
    @Transactional
    public int enforceUnsignedAgreements() {
        LocalDateTime now = LocalDateTime.now();

        // 1. Quét thỏa thuận PENDING quá hạn 72 giờ: Chuyển sang EXPIRED và gắn cờ (FLAGGED) tài khoản
        List<CompanyAgreement> overdueAgreements = agreementRepository
                .findByStatusAndDeadlineAtBefore(AgreementStatus.PENDING, now);

        int flaggedCount = 0;
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

                if (targetUser != null && targetUser.getStatus() != UserStatus.FLAGGED) {
                    targetUser.setStatus(UserStatus.FLAGGED);
                    userRepository.save(targetUser);

                    log.warn("FLAGGED Company Admin user {} (id={}) of company {} due to unsigned agreement past 72h deadline. Login remains permitted.",
                            targetUser.getEmail(), targetUser.getId(), agreement.getCompany() != null ? agreement.getCompany().getId() : "null");
                    flaggedCount++;
                }
            } catch (Exception ex) {
                log.error("Error flagging trial agreement for agreement id {}: {}", agreement.getId(), ex.getMessage(), ex);
            }
        }

        // 2. Quét tài khoản đã bị gắn cờ quá 30 ngày: Xóa cứng vĩnh viễn khỏi Keycloak và DB
        LocalDateTime hardDeleteCutoff = now.minusDays(30);
        List<CompanyAgreement> expiredPast30Days = agreementRepository
                .findByStatusAndDeadlineAtBefore(AgreementStatus.EXPIRED, hardDeleteCutoff);

        for (CompanyAgreement agreement : expiredPast30Days) {
            try {
                User flaggedUser = agreement.getUser();
                Long companyId = agreement.getCompany() != null ? agreement.getCompany().getId() : null;

                if (flaggedUser != null) {
                    if (keycloakAdminClient != null
                            && flaggedUser.getKeycloakId() != null
                            && !flaggedUser.getKeycloakId().isBlank()) {
                        try {
                            keycloakAdminClient.deleteUser(flaggedUser.getKeycloakId());
                        } catch (Exception kcEx) {
                            log.warn("Keycloak user {} already absent during 30-day trial cleanup", flaggedUser.getKeycloakId());
                        }
                    }
                    agreement.setUser(null);
                    userRepository.delete(flaggedUser);
                    log.info("HARD DELETED user {} from Keycloak and DB after 30 days of being flagged", flaggedUser.getEmail());
                }

                agreementRepository.delete(agreement);

                if (companyId != null && userRepository.findByCompanyId(companyId).isEmpty()) {
                    companyRepository.deleteById(companyId);
                    log.info("HARD DELETED company id={} after all users removed in 30-day cleanup", companyId);
                }
            } catch (Exception ex) {
                log.error("Error hard-deleting expired trial agreement {}: {}", agreement.getId(), ex.getMessage(), ex);
            }
        }

        return flaggedCount;
    }
}
