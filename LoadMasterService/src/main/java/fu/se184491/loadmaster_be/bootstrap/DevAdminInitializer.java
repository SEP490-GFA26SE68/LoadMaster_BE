package fu.se184491.loadmaster_be.bootstrap;

import fu.se184491.loadmaster_be.client.KeycloakAdminClient;
import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.account.UserStatus;
import fu.se184491.loadmaster_be.dto.request.account.KeycloakUserRepresentation;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import fu.se184491.loadmaster_be.service.security.ServiceTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Profile("dev")
@Order(2)
@RequiredArgsConstructor
public class DevAdminInitializer implements ApplicationRunner {

    private final UserRepository userRepository;
    private final KeycloakAdminClient keycloakAdminClient;
    private final ServiceTokenProvider serviceTokenProvider;

    @Value("${app.bootstrap.admin.email}")
    private String adminEmail;

    @Value("${app.bootstrap.admin.password}")
    private String adminPassword;

    @Value("${app.bootstrap.admin.full-name}")
    private String adminFullName;

    @Override
    public void run(ApplicationArguments args) {

        User dbUser = userRepository
                .findByEmail(adminEmail)
                .orElse(null);

        String keycloakUserId =
                keycloakAdminClient.findUserIdByEmail(adminEmail);

        // 1. Cả hai đều có
        if (dbUser != null && keycloakUserId != null) {
            return;
        }

        // 2. Keycloak có, DB chưa có
        if (dbUser == null && keycloakUserId != null) {

            User admin = User.builder()
                    .keycloakId(keycloakUserId)
                    .email(adminEmail)
                    .fullName(adminFullName)
                    .status(UserStatus.ACTIVE)
                    .userRoleType(UserRole.SYSTEM_ADMIN)
                    .build();

            userRepository.save(admin);

            return;
        }

        // 3. DB có, Keycloak không có
        if (dbUser != null) {
            throw new IllegalStateException(
                    "Admin exists in database but not in Keycloak"
            );
        }

        // 4. Cả hai chưa có -> tạo mới
        String createdKeycloakUserId = null;

        try {
            createdKeycloakUserId =
                    keycloakAdminClient.createUser(
                            adminEmail,
                            adminFullName
                    );

            keycloakAdminClient.setPassword(
                    createdKeycloakUserId,
                    adminPassword
            );

            keycloakAdminClient.assignRealmRole(
                    createdKeycloakUserId,
                    UserRole.SYSTEM_ADMIN.name()
            );

            User admin = User.builder()
                    .keycloakId(createdKeycloakUserId)
                    .email(adminEmail)
                    .fullName(adminFullName)
                    .status(UserStatus.ACTIVE)
                    .userRoleType(UserRole.SYSTEM_ADMIN)
                    .build();

            userRepository.save(admin);

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


}