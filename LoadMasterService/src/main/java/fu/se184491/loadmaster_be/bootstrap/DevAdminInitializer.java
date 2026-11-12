package fu.se184491.loadmaster_be.bootstrap;

import fu.se184491.loadmaster_be.client.KeycloakAdminClient;
import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.account.UserStatus;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.helpers.KeycloakAccounts;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Consumer;

/**
 * Bảo đảm có tài khoản quản trị hệ thống khi chạy profile dev.
 *
 * <p>Không bao giờ xoá hàng {@code users} của admin: DB có thể đang dùng chung (Supabase) với một Keycloak khác Keycloak của
 * máy đang chạy. Khi admin trong DB gắn với Keycloak khác, mặc định chỉ ghi cảnh báo; muốn gắn lại admin vào Keycloak đang nối
 * (vừa chuyển sang Keycloak server mới) thì đặt {@code DEV_ADMIN_RELINK=true} — khi đó chỉ cập nhật {@code keycloak_id}.
 */
@Slf4j
@Component
@Profile("dev")
@Order(2)
@RequiredArgsConstructor
public class DevAdminInitializer implements ApplicationRunner {

    private final UserRepository userRepository;
    private final KeycloakAdminClient keycloakAdminClient;
    private final PlatformTransactionManager transactionManager;

    @Value("${app.bootstrap.admin.email}")
    private String adminEmail;

    @Value("${app.bootstrap.admin.password}")
    private String adminPassword;

    @Value("${app.bootstrap.admin.full-name}")
    private String adminFullName;

    @Value("${app.bootstrap.admin.relink:false}")
    private boolean relink;

    @Override
    public void run(ApplicationArguments args) {

        User dbUser = userRepository
                .findByEmail(adminEmail)
                .orElse(null);

        String keycloakUserId =
                keycloakAdminClient.findUserIdByEmail(adminEmail);

        // 1. Cả hai đều có
        if (dbUser != null && keycloakUserId != null) {
            if (!keycloakUserId.equals(dbUser.getKeycloakId())) {
                relinkOrWarn(dbUser, keycloakUserId);
            }
            return;
        }

        // 2. Keycloak có, DB chưa có
        if (dbUser == null && keycloakUserId != null) {
            userRepository.save(newAdmin(keycloakUserId));
            return;
        }

        // 3. DB có, Keycloak không có (Keycloak server mới, hoặc DB dùng chung với Keycloak khác)
        if (dbUser != null) {
            if (!relink) {
                warnForeignKeycloak();
                return;
            }
            User existing = dbUser;
            createInKeycloak(createdId -> {
                existing.setKeycloakId(createdId);
                userRepository.save(existing);
            });
            log.info("Đã tạo admin {} trên Keycloak đang nối và gắn lại vào hàng users có sẵn", adminEmail);
            return;
        }

        // 4. Cả hai chưa có -> tạo mới
        createInKeycloak(createdId -> userRepository.save(newAdmin(createdId)));
    }

    private void relinkOrWarn(User dbUser, String keycloakUserId) {
        if (!relink) {
            warnForeignKeycloak();
            return;
        }
        dbUser.setKeycloakId(keycloakUserId);
        userRepository.save(dbUser);
        log.info("Đã gắn lại admin {} vào tài khoản trên Keycloak đang nối", adminEmail);
    }

    private void warnForeignKeycloak() {
        log.warn("Admin {} trong DB gắn với một Keycloak khác Keycloak đang nối (DB có thể đang dùng chung). Không sửa gì; "
                + "đặt DEV_ADMIN_RELINK=true nếu muốn gắn lại admin vào Keycloak này.", adminEmail);
    }

    /**
     * Tạo admin trên Keycloak rồi chạy {@code saveToDb} trong cùng một transaction: lưu DB lỗi thì rollback và
     * {@link KeycloakAccounts#provision} xoá lại tài khoản Keycloak vừa tạo.
     */
    private void createInKeycloak(Consumer<String> saveToDb) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            // Mật khẩu lấy từ DEV_ADMIN_PASSWORD: không bắt đổi, để đăng nhập ngay trên màn của app được
            String keycloakUserId = KeycloakAccounts.provision(keycloakAdminClient, userRepository, adminEmail,
                    adminFullName, adminPassword, false, UserRole.SYSTEM_ADMIN, false);
            saveToDb.accept(keycloakUserId);
        });
    }

    private User newAdmin(String keycloakUserId) {
        return User.builder()
                .keycloakId(keycloakUserId)
                .email(adminEmail)
                .fullName(adminFullName)
                .status(UserStatus.ACTIVE)
                .userRoleType(UserRole.SYSTEM_ADMIN)
                .build();
    }
}
