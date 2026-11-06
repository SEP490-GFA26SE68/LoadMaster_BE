package fu.se184491.loadmaster_be.service.account;

import fu.se184491.loadmaster_be.client.KeycloakAdminClient;
import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.account.UserStatus;
import fu.se184491.loadmaster_be.dto.request.account.KeycloakRoleRepresentation;
import fu.se184491.loadmaster_be.dto.request.account.KeycloakUserRepresentation;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Bù user thiếu giữa Keycloak đang nối và bảng {@code users}.
 *
 * <p>Mật khẩu chỉ lưu trên Keycloak (hash, không đọc lại được) — bảng {@code users} không có cột password.
 * User đã có trên Keycloak: tạo hồ sơ DB, login vẫn dùng mật khẩu Keycloak hiện có.
 * User chỉ có trên DB: tạo trên Keycloak với {@code app.bootstrap.sync.password}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserIdentitySyncService {

    private final UserRepository userRepository;
    private final KeycloakAdminClient keycloakAdminClient;

    @Value("${app.bootstrap.sync.password:}")
    private String syncPassword;

    public record Result(int createdInDb, int createdInKeycloak, int relinked) {}

    @Transactional
    public Result syncMissingUsers() {
        List<KeycloakUserRepresentation> keycloakUsers = keycloakAdminClient.listUsers();
        List<User> dbUsers = userRepository.findAll();

        Map<String, User> dbByEmail = new HashMap<>();
        Map<String, User> dbByKeycloakId = new HashMap<>();
        for (User user : dbUsers) {
            if (user.getEmail() != null && !user.getEmail().isBlank()) {
                dbByEmail.putIfAbsent(normalize(user.getEmail()), user);
            }
            if (user.getKeycloakId() != null && !user.getKeycloakId().isBlank()) {
                dbByKeycloakId.putIfAbsent(user.getKeycloakId(), user);
            }
        }

        Map<String, KeycloakUserRepresentation> kcByEmail = new HashMap<>();
        Map<String, KeycloakUserRepresentation> kcById = new HashMap<>();
        for (KeycloakUserRepresentation kcUser : keycloakUsers) {
            if (isServiceAccount(kcUser)) {
                continue;
            }
            if (kcUser.id() != null) {
                kcById.putIfAbsent(kcUser.id(), kcUser);
            }
            String email = resolveEmail(kcUser);
            if (email != null) {
                kcByEmail.putIfAbsent(email, kcUser);
            }
        }

        int createdInDb = 0;
        int relinked = 0;
        for (KeycloakUserRepresentation kcUser : kcById.values()) {
            String email = resolveEmail(kcUser);
            if (email == null) {
                log.warn("Bỏ qua user Keycloak {} vì không có email", kcUser.id());
                continue;
            }
            User byId = dbByKeycloakId.get(kcUser.id());
            User byEmail = dbByEmail.get(email);
            if (byId != null) {
                continue;
            }
            if (byEmail != null) {
                if (!kcUser.id().equals(byEmail.getKeycloakId())) {
                    userRepository.updateKeycloakId(byEmail.getId(), kcUser.id());
                    byEmail.setKeycloakId(kcUser.id());
                    dbByKeycloakId.put(kcUser.id(), byEmail);
                    relinked++;
                    log.info("Gắn lại users.id={} email={} với keycloak_id={}", byEmail.getId(), email, kcUser.id());
                }
                continue;
            }

            Optional<UserRole> role = resolveRole(kcUser.id());
            if (role.isEmpty()) {
                log.warn("Bỏ qua user Keycloak {} ({}) vì không có realm role LoadMaster", kcUser.id(), email);
                continue;
            }

            User created = newDbUser(kcUser.id(), email, fullName(kcUser), role.get(), kcUser.enabled());
            created.prePersist();
            created = userRepository.save(created);
            dbByEmail.put(email, created);
            dbByKeycloakId.put(kcUser.id(), created);
            createdInDb++;
            log.info("Tạo users từ Keycloak: email={} role={} keycloak_id={}", email, role.get(), kcUser.id());
        }

        int createdInKeycloak = 0;
        for (User dbUser : dbUsers) {
            String email = dbUser.getEmail() == null ? null : normalize(dbUser.getEmail());
            if (email == null) {
                continue;
            }
            if (kcByEmail.containsKey(email)
                    || (dbUser.getKeycloakId() != null && kcById.containsKey(dbUser.getKeycloakId()))) {
                continue;
            }
            createInKeycloak(dbUser, email);
            createdInKeycloak++;
        }

        log.info(
                "Đồng bộ user Keycloak ↔ users: tạo DB={}, tạo Keycloak={}, gắn lại={}",
                createdInDb,
                createdInKeycloak,
                relinked
        );
        return new Result(createdInDb, createdInKeycloak, relinked);
    }

    private void createInKeycloak(User dbUser, String email) {
        if (syncPassword == null || syncPassword.isBlank()) {
            throw new IllegalStateException(
                    "Thiếu app.bootstrap.sync.password để tạo user Keycloak từ bảng users"
            );
        }

        String createdKeycloakUserId = null;
        try {
            createdKeycloakUserId = keycloakAdminClient.createUser(dbUser.getEmail(), dbUser.getFullName());
            keycloakAdminClient.setPassword(createdKeycloakUserId, syncPassword, false);
            keycloakAdminClient.assignRealmRole(createdKeycloakUserId, dbUser.getUserRoleType().name());
            userRepository.updateKeycloakId(dbUser.getId(), createdKeycloakUserId);
            log.info(
                    "Tạo Keycloak từ users.id={} email={} role={} — mật khẩu = app.bootstrap.sync.password",
                    dbUser.getId(),
                    email,
                    dbUser.getUserRoleType()
            );
        } catch (Exception ex) {
            if (createdKeycloakUserId != null) {
                try {
                    keycloakAdminClient.deleteUser(createdKeycloakUserId);
                } catch (Exception ignored) {
                }
            }
            throw ex;
        }
    }

    private Optional<UserRole> resolveRole(String keycloakUserId) {
        List<KeycloakRoleRepresentation> roles = keycloakAdminClient.listRealmRoles(keycloakUserId);
        for (UserRole role : UserRole.values()) {
            for (KeycloakRoleRepresentation kcRole : roles) {
                if (role.name().equals(kcRole.name())) {
                    return Optional.of(role);
                }
            }
        }
        return Optional.empty();
    }

    private static User newDbUser(
            String keycloakId,
            String email,
            String fullName,
            UserRole role,
            Boolean enabled
    ) {
        return User.builder()
                .keycloakId(keycloakId)
                .email(email)
                .fullName(fullName)
                .status(Boolean.FALSE.equals(enabled) ? UserStatus.INACTIVE : UserStatus.ACTIVE)
                .userRoleType(role)
                .build();
    }

    private static boolean isServiceAccount(KeycloakUserRepresentation user) {
        if (user.serviceAccountClientId() != null && !user.serviceAccountClientId().isBlank()) {
            return true;
        }
        String username = user.username();
        return username != null && username.startsWith("service-account-");
    }

    private static String resolveEmail(KeycloakUserRepresentation user) {
        if (user.email() != null && !user.email().isBlank()) {
            return normalize(user.email());
        }
        if (user.username() != null && user.username().contains("@")) {
            return normalize(user.username());
        }
        return null;
    }

    private static String fullName(KeycloakUserRepresentation user) {
        String first = user.firstName() == null ? "" : user.firstName().trim();
        String last = user.lastName() == null ? "" : user.lastName().trim();
        String composed = (first + " " + last).trim();
        if (!composed.isEmpty()) {
            return composed;
        }
        String email = resolveEmail(user);
        if (email != null) {
            return email;
        }
        return user.username();
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
