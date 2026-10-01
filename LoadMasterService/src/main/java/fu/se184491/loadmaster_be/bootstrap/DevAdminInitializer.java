package fu.se184491.loadmaster_be.bootstrap;

import fu.se184491.loadmaster_be.client.KeycloakAdminClient;
import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.account.UserStatus;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
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

    @Value("${app.bootstrap.admin.email}")
    private String adminEmail;

    @Value("${app.bootstrap.admin.password}")
    private String adminPassword;

    @Value("${app.bootstrap.admin.full-name}")
    private String adminFullName;

    @Override
    public void run(ApplicationArguments args) {

        if (userRepository.existsByEmail(adminEmail)) {
            return;
        }

        String keycloakUserId = null;

        try {
            keycloakUserId = keycloakAdminClient.createUser(
                    adminEmail,
                    adminFullName
            );

            keycloakAdminClient.setPassword(
                    keycloakUserId,
                    adminPassword
            );

            keycloakAdminClient.assignRealmRole(
                    keycloakUserId,
                    UserRole.SYSTEM_ADMIN.name()
            );

            User admin = User.builder()
                    .keycloakId(keycloakUserId)
                    .email(adminEmail)
                    .fullName(adminFullName)
                    .status(UserStatus.ACTIVE)
                    .userRoleType(UserRole.SYSTEM_ADMIN)
                    .build();

            userRepository.save(admin);

        } catch (Exception ex) {

            if (keycloakUserId != null) {
                try {
                    keycloakAdminClient.deleteUser(keycloakUserId);
                } catch (Exception ignored) {
                }
            }

            throw ex;
        }
    }
}