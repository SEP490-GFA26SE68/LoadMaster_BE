package fu.se184491.loadmaster_be.service.company;

import fu.se184491.loadmaster_be.client.KeycloakAdminClient;
import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.account.UserStatus;
import fu.se184491.loadmaster_be.constant.account.UserType;
import fu.se184491.loadmaster_be.constant.company.AgreementStatus;
import fu.se184491.loadmaster_be.constant.company.AgreementType;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.company.CompanyAgreement;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import fu.se184491.loadmaster_be.repository.company.CompanyAgreementRepository;
import fu.se184491.loadmaster_be.repository.company.CompanyRepository;
import fu.se184491.loadmaster_be.repository.support.SupportTicketRepository;
import fu.se184491.loadmaster_be.repository.support.TicketReplyRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * Job duy nhất thực thi hạn ký thỏa thuận dùng thử. Mỗi lần chạy:
 *
 * <ol>
 *   <li>Thỏa thuận PENDING quá hạn 72 giờ → EXPIRED (ghi {@code expiredAt}) và gắn cờ FLAGGED tài khoản của nó (thỏa
 *       thuận không gắn người thì Quản trị công ty), cả TRIAL lẫn OFFICIAL. Tài khoản bị gắn cờ vẫn đăng nhập được.</li>
 *   <li>Thỏa thuận EXPIRED chưa có {@code expiredAt} (bản ghi có trước cột này) → {@code expiredAt} = lúc chạy, nên mốc
 *       30 ngày của chúng bắt đầu từ đây chứ không phải từ hạn 72 giờ cũ.</li>
 *   <li>Thỏa thuận EXPIRED quá 30 ngày kể từ {@code expiredAt} → xóa cứng tài khoản nếu đó là tài khoản TRIAL còn bị
 *       gắn cờ, cùng các yêu cầu hỗ trợ tài khoản đó đã gửi (trả lời của chúng xoá trước, vì khóa ngoại). Tài khoản OFFICIAL chỉ bị
 *       gắn cờ, không bao giờ bị xóa, công ty của họ cũng vậy. Bước này chỉ chạy khi bật
 *       {@code app.trial.hard-delete-enabled} (mặc định tắt); tắt thì gắn cờ và khoá vẫn chạy.</li>
 * </ol>
 *
 * <p>Mỗi thỏa thuận chạy trong transaction riêng (REQUIRES_NEW): một bản ghi lỗi không kéo các bản ghi khác rollback
 * và không chặn lần xử lý của chúng. Khi xóa, DB xóa và commit trước, rồi mới xóa trên Keycloak; Keycloak lỗi thì ghi
 * log, không ném. Công ty chỉ bị xóa khi là công ty dùng thử và không còn người dùng nào; khóa ngoại chặn thì bỏ qua.
 */
@Component
@Slf4j
public class TrialAgreementEnforcementJob {

    public static final int HARD_DELETE_AFTER_DAYS = 30;

    private final CompanyAgreementRepository agreementRepository;
    private final UserRepository userRepository;
    private final CompanyRepository companyRepository;
    private final SupportTicketRepository supportTicketRepository;
    private final TicketReplyRepository ticketReplyRepository;
    private final KeycloakAdminClient keycloakAdminClient;
    private final TransactionTemplate requiresNew;
    private final boolean hardDeleteEnabled;

    public TrialAgreementEnforcementJob(
            CompanyAgreementRepository agreementRepository,
            UserRepository userRepository,
            CompanyRepository companyRepository,
            SupportTicketRepository supportTicketRepository,
            TicketReplyRepository ticketReplyRepository,
            KeycloakAdminClient keycloakAdminClient,
            PlatformTransactionManager transactionManager,
            @Value("${app.trial.hard-delete-enabled:false}") boolean hardDeleteEnabled
    ) {
        this.agreementRepository = agreementRepository;
        this.userRepository = userRepository;
        this.companyRepository = companyRepository;
        this.supportTicketRepository = supportTicketRepository;
        this.ticketReplyRepository = ticketReplyRepository;
        this.keycloakAdminClient = keycloakAdminClient;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.hardDeleteEnabled = hardDeleteEnabled;
    }

    /**
     * Chạy mỗi 30 phút.
     *
     * @return số tài khoản vừa bị gắn cờ trong lần chạy này
     */
    public int enforceUnsignedAgreements() {
        LocalDateTime now = LocalDateTime.now();
        int flagged = flagOverdueAgreements(now);
        fillMissingExpiredAt(now);
        if (hardDeleteEnabled) {
            hardDeleteExpiredTrialAccounts(now);
        }
        return flagged;
    }

    // ── 1. Gắn cờ ────────────────────────────────────────────────────────────────────────────────────────────────

    private int flagOverdueAgreements(LocalDateTime now) {
        int flagged = 0;
        for (Long agreementId : agreementRepository.findIdsByStatusAndDeadlineAtBefore(AgreementStatus.PENDING, now)) {
            try {
                if (Boolean.TRUE.equals(requiresNew.execute(tx -> flagAgreement(agreementId, now)))) {
                    flagged++;
                }
            } catch (RuntimeException ex) {
                log.error("Could not flag overdue trial agreement {}; it will be retried on the next run", agreementId, ex);
            }
        }
        return flagged;
    }

    private boolean flagAgreement(Long agreementId, LocalDateTime now) {
        CompanyAgreement agreement = agreementRepository.findById(agreementId).orElse(null);
        // Đọc lại trong transaction: giữa lúc lấy danh sách và lúc này thỏa thuận có thể đã được ký hoặc gia hạn.
        if (agreement == null
                || agreement.getStatus() != AgreementStatus.PENDING
                || !agreement.getDeadlineAt().isBefore(now)) {
            return false;
        }

        agreement.setStatus(AgreementStatus.EXPIRED);
        agreement.setExpiredAt(now);
        agreementRepository.save(agreement);

        User target = resolveTargetUser(agreement);
        if (target == null) {
            log.warn("Trial agreement {} expired but has no user or company admin to flag", agreementId);
            return false;
        }
        if (target.getStatus() == UserStatus.FLAGGED) {
            return false;
        }

        target.setStatus(UserStatus.FLAGGED);
        userRepository.save(target);
        log.warn("FLAGGED {} user id={} of company {}: agreement {} unsigned past its 72h deadline. Login remains permitted.",
                target.getUserType(), target.getId(), companyIdOf(agreement), agreementId);
        return true;
    }

    // ── 2. Điền expiredAt cho bản ghi cũ ─────────────────────────────────────────────────────────────────────────

    private void fillMissingExpiredAt(LocalDateTime now) {
        try {
            Integer filled = requiresNew.execute(tx ->
                    agreementRepository.fillMissingExpiredAt(AgreementStatus.EXPIRED, now));
            if (filled != null && filled > 0) {
                log.info("Set expiredAt={} on {} expired agreement(s) that had none; their {}-day deletion window starts now",
                        now, filled, HARD_DELETE_AFTER_DAYS);
            }
        } catch (RuntimeException ex) {
            // Bản ghi chưa có expiredAt không khớp truy vấn xóa (expiredAt < mốc), nên lỗi ở đây không làm xóa sớm.
            log.error("Could not set expiredAt on legacy expired agreements", ex);
        }
    }

    // ── 3. Xóa cứng tài khoản dùng thử ───────────────────────────────────────────────────────────────────────────

    private void hardDeleteExpiredTrialAccounts(LocalDateTime now) {
        LocalDateTime cutoff = now.minusDays(HARD_DELETE_AFTER_DAYS);
        for (Long agreementId : agreementRepository.findIdsByStatusAndExpiredAtBefore(AgreementStatus.EXPIRED, cutoff)) {
            DeletedTrialAccount deleted;
            try {
                deleted = requiresNew.execute(tx -> deleteTrialAccount(agreementId, cutoff));
            } catch (RuntimeException ex) {
                log.error("Could not hard-delete the trial account of agreement {}; nothing was deleted", agreementId, ex);
                continue;
            }
            if (deleted == null) {
                continue;
            }
            // Transaction trên đã commit: tới đây mới xóa ở Keycloak, rồi tới công ty (nếu là công ty dùng thử).
            deleteKeycloakUser(deleted);
            deleteTrialCompanyIfEmpty(deleted);
        }
    }

    private DeletedTrialAccount deleteTrialAccount(Long agreementId, LocalDateTime cutoff) {
        CompanyAgreement agreement = agreementRepository.findById(agreementId).orElse(null);
        if (agreement == null
                || agreement.getStatus() != AgreementStatus.EXPIRED
                || agreement.getExpiredAt() == null
                || !agreement.getExpiredAt().isBefore(cutoff)) {
            return null;
        }

        User user = resolveTargetUser(agreement);
        if (user == null) {
            log.debug("Expired agreement {} has no user to delete", agreementId);
            return null;
        }
        if (user.getUserType() != UserType.TRIAL) {
            log.debug("Expired agreement {} belongs to {} user id={}; only TRIAL accounts are deleted",
                    agreementId, user.getUserType(), user.getId());
            return null;
        }
        if (user.getStatus() != UserStatus.FLAGGED) {
            // Hỗ trợ khách hàng đã mở khóa tài khoản: không xóa người đang được phép dùng tiếp.
            log.info("Trial user id={} of expired agreement {} is {} (no longer flagged); not deleted",
                    user.getId(), agreementId, user.getStatus());
            return null;
        }

        DeletedTrialAccount deleted = new DeletedTrialAccount(
                user.getId(),
                user.getKeycloakId(),
                companyIdOf(agreement),
                agreement.getAgreementType() == AgreementType.TRIAL_TERMS);

        agreementRepository.delete(agreement);
        deleteSupportTicketsOf(user);
        userRepository.delete(user);
        log.info("HARD DELETED trial user id={} of company {} from the DB, {} days after agreement {} expired",
                deleted.userId(), deleted.companyId(), HARD_DELETE_AFTER_DAYS, agreementId);
        return deleted;
    }

    /** Yêu cầu hỗ trợ của tài khoản bị xóa cứng: trả lời trước rồi tới phiếu, cùng transaction với việc xóa tài khoản. */
    private void deleteSupportTicketsOf(User user) {
        List<Long> ticketIds = supportTicketRepository.findIdsByRequesterId(user.getId());
        if (ticketIds == null || ticketIds.isEmpty()) {
            return;
        }
        ticketReplyRepository.deleteByTicketIdIn(ticketIds);
        supportTicketRepository.deleteAllByIdInBatch(ticketIds);
    }

    private void deleteKeycloakUser(DeletedTrialAccount deleted) {
        String keycloakId = deleted.keycloakId();
        if (keycloakId == null || keycloakId.isBlank()) {
            return;
        }
        try {
            keycloakAdminClient.deleteUser(keycloakId);
        } catch (HttpClientErrorException.NotFound ex) {
            log.info("Keycloak user {} of deleted trial user id={} was already absent", keycloakId, deleted.userId());
        } catch (RuntimeException ex) {
            log.error("Trial user id={} was deleted from the DB but Keycloak user {} could not be deleted; remove it manually",
                    deleted.userId(), keycloakId, ex);
        }
    }

    private void deleteTrialCompanyIfEmpty(DeletedTrialAccount deleted) {
        Long companyId = deleted.companyId();
        if (companyId == null || !deleted.trialCompany()) {
            return;
        }
        try {
            requiresNew.executeWithoutResult(tx -> {
                if (!userRepository.findByCompanyId(companyId).isEmpty()) {
                    log.info("Trial company {} still has users; kept", companyId);
                    return;
                }
                companyRepository.deleteById(companyId);
                log.info("HARD DELETED trial company {} after its last user was removed", companyId);
            });
        } catch (RuntimeException ex) {
            log.warn("Trial company {} has no users left but could not be deleted (still referenced by other records); kept",
                    companyId, ex);
        }
    }

    // ── Chung ────────────────────────────────────────────────────────────────────────────────────────────────────

    /** Người của thỏa thuận; thỏa thuận không gắn người thì Quản trị công ty (mã nhỏ nhất) của công ty đó. */
    private User resolveTargetUser(CompanyAgreement agreement) {
        if (agreement.getUser() != null) {
            return agreement.getUser();
        }
        Long companyId = companyIdOf(agreement);
        if (companyId == null) {
            return null;
        }
        return userRepository.findByCompanyId(companyId).stream()
                .filter(user -> user.getUserRoleType() == UserRole.ADMIN)
                .min(Comparator.comparing(User::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .orElse(null);
    }

    private static Long companyIdOf(CompanyAgreement agreement) {
        return agreement.getCompany() != null ? agreement.getCompany().getId() : null;
    }

    private record DeletedTrialAccount(Long userId, String keycloakId, Long companyId, boolean trialCompany) {
    }
}
