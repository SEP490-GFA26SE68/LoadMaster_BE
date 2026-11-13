package fu.se184491.loadmaster_be.helpers;

import fu.se184491.loadmaster_be.client.KeycloakAdminClient;
import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Tạo tài khoản Keycloak cho một người dùng sắp ghi vào DB, dùng chung cho tạo người dùng, duyệt dùng thử và
 * đăng ký trả phí. Keycloak không nằm trong transaction của DB nên người dùng Keycloak vừa tạo được xoá lại khi
 * transaction đang chạy rollback (kể cả lỗi lúc commit). Không xoá được thì ghi log lỗi kèm mã để dọn tay.
 */
@Slf4j
public final class KeycloakAccounts {

    private KeycloakAccounts() {
    }

    /**
     * @param reuseUnlinked dùng lại người dùng Keycloak đã có cùng email nếu chưa gắn với người dùng nào trong DB
     *                      (tài khoản mồ côi của lần trước); đã gắn thì EMAIL_ALREADY_EXISTS. Tắt thì luôn tạo mới.
     * @return mã người dùng Keycloak
     */
    public static String provision(
            KeycloakAdminClient keycloak,
            UserRepository users,
            String email,
            String fullName,
            String password,
            boolean temporaryPassword,
            UserRole role,
            boolean reuseUnlinked
    ) {
        String keycloakId = reuseUnlinked ? keycloak.findUserIdByEmail(email) : null;
        boolean created = false;
        if (keycloakId != null) {
            if (users.findByKeycloakId(keycloakId).isPresent()) {
                throw new AppException(ErrorCode.EMAIL_ALREADY_EXISTS,
                        "Email '" + email + "' đã được sử dụng cho một tài khoản khác trong hệ thống.");
            }
            keycloak.updateUserProfile(keycloakId, fullName);
        } else {
            keycloakId = keycloak.createUser(email, fullName);
            created = true;
            deleteOnRollback(keycloak, keycloakId);
        }
        try {
            keycloak.setPassword(keycloakId, password, temporaryPassword);
            keycloak.assignRealmRole(keycloakId, role.name());
        } catch (RuntimeException ex) {
            // Không có transaction để rollback thì xoá ngay tại đây
            if (created && !TransactionSynchronizationManager.isSynchronizationActive()) {
                delete(keycloak, keycloakId);
            }
            throw ex;
        }
        return keycloakId;
    }

    private static void deleteOnRollback(KeycloakAdminClient keycloak, String keycloakId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    delete(keycloak, keycloakId);
                }
            }
        });
    }

    private static void delete(KeycloakAdminClient keycloak, String keycloakId) {
        try {
            keycloak.deleteUser(keycloakId);
            log.info("Đã xoá người dùng Keycloak {} vì việc tạo tài khoản không ghi được", keycloakId);
        } catch (RuntimeException ex) {
            log.error("Không xoá được người dùng Keycloak {} sau khi việc tạo tài khoản thất bại, cần dọn tay: {}",
                    keycloakId, ex.getMessage(), ex);
        }
    }
}
