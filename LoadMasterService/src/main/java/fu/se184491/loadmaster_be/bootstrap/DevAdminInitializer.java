package fu.se184491.loadmaster_be.bootstrap;

import fu.se184491.loadmaster_be.client.KeycloakAdminClient;
import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.account.UserStatus;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

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

        String role = UserRole.SYSTEM_ADMIN.name();

        // Đảm bảo realm role SYSTEM_ADMIN tồn tại
        keycloakAdminClient.ensureRealmRoleExists(role);

        User dbUser = userRepository
                .findByEmail(adminEmail)
                .orElse(null);

        String keycloakUserId =
                keycloakAdminClient.findUserIdByEmail(adminEmail);

        // CASE 1: CẢ HAI ĐỀU CHƯA CÓ
        if (dbUser == null && keycloakUserId == null) {

            log.info("Admin chưa tồn tại ở cả DB và Keycloak");

            createInKeycloak(createdId ->
                    userRepository.save(newAdmin(createdId))
            );

            log.info("Đã seed admin vào cả PostgreSQL và Keycloak");
            return;
        }

        // CASE 2: KEYCLOAK CÓ, DATABASE CHƯA CÓ
        if (dbUser == null) {

            log.info("Admin đã có trên Keycloak, đang tạo trong DB");

            // Đảm bảo Keycloak user có role
            keycloakAdminClient.assignRealmRole(
                    keycloakUserId, role
            );

            userRepository.save(newAdmin(keycloakUserId));

            log.info("Đã đồng bộ admin từ Keycloak sang DB");
            return;
        }

        // CASE 3: DATABASE CÓ, KEYCLOAK CHƯA CÓ
        if (keycloakUserId == null) {

            if (!relink) {
                warnForeignKeycloak();
                return;
            }

            log.info("Admin đã có trong DB, đang tạo trên Keycloak");

            createInKeycloak(createdId -> {
                dbUser.setKeycloakId(createdId);
                userRepository.save(dbUser);
            });

            log.info("Đã đồng bộ admin từ DB sang Keycloak");
            return;
        }

        // CASE 4: CẢ HAI ĐỀU CÓ
        if (!keycloakUserId.equals(dbUser.getKeycloakId())) {

            if (!relink) {
                warnForeignKeycloak();
                return;
            }

            relinkOrWarn(dbUser, keycloakUserId);
        }

        // Đảm bảo admin hiện có được gán realm role
        keycloakAdminClient.assignRealmRole(
                keycloakUserId, role
        );

        log.info("Admin đã đồng bộ thành công: {}", adminEmail);
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

    /** Tạo admin trên Keycloak rồi chạy {@code saveToDb}; lưu DB lỗi thì xoá tài khoản Keycloak vừa tạo. */
    private void createInKeycloak(Consumer<String> saveToDb) {
        String createdKeycloakUserId = null;

        try {
            createdKeycloakUserId =
                    keycloakAdminClient.createUser(
                            adminEmail,
                            adminFullName
                    );

            // Mật khẩu lấy từ DEV_ADMIN_PASSWORD: không bắt đổi, để đăng nhập ngay trên màn của app được
            keycloakAdminClient.setPassword(
                    createdKeycloakUserId,
                    adminPassword,
                    false
            );

            keycloakAdminClient.assignRealmRole(
                    createdKeycloakUserId,
                    UserRole.SYSTEM_ADMIN.name()
            );

            saveToDb.accept(createdKeycloakUserId);

        } catch (Exception ex) {

            if (createdKeycloakUserId != null) {
                try {
                    keycloakAdminClient.deleteUser(
                            createdKeycloakUserId
                    );
                } catch (Exception ignored) {
                }
            }

            throw ex;
        }
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
