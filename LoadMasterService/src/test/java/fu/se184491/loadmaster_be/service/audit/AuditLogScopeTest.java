package fu.se184491.loadmaster_be.service.audit;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditLogScopeTest {

    @Test
    void systemAdminWithoutFilterReadsEverything() {
        AuditLogScope scope = AuditLogScope.of(UserRole.SYSTEM_ADMIN, null, null);
        assertThat(scope.everything()).isTrue();
        assertThat(scope.platformOnly()).isFalse();
        assertThat(scope.companyId()).isNull();
    }

    @Test
    void systemAdminFiltersByCompany() {
        AuditLogScope scope = AuditLogScope.of(UserRole.SYSTEM_ADMIN, null, "7");
        assertThat(scope.everything()).isFalse();
        assertThat(scope.companyId()).isEqualTo(7L);
    }

    @Test
    void systemAdminFiltersPlatformEventsWithPlatformKeyword() {
        AuditLogScope scope = AuditLogScope.of(UserRole.SYSTEM_ADMIN, null, "platform");
        assertThat(scope.platformOnly()).isTrue();
        assertThat(scope.companyId()).isNull();
    }

    @Test
    void systemAdminWithUnknownCompanyFilterIsBadRequest() {
        assertThatThrownBy(() -> AuditLogScope.of(UserRole.SYSTEM_ADMIN, null, "abc"))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
    }

    @Test
    void companyAdminIsAlwaysScopedToOwnCompany() {
        assertThat(AuditLogScope.of(UserRole.ADMIN, 3L, null).companyId()).isEqualTo(3L);
        AuditLogScope other = AuditLogScope.of(UserRole.ADMIN, 3L, "9");
        assertThat(other.companyId()).isEqualTo(3L);
        assertThat(other.everything()).isFalse();
        assertThat(AuditLogScope.of(UserRole.ADMIN, 3L, "platform").platformOnly()).isFalse();
    }

    @Test
    void companyAdminWithoutCompanyIsRejected() {
        assertThatThrownBy(() -> AuditLogScope.of(UserRole.ADMIN, null, null))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.USER_COMPANY_NOT_FOUND));
    }

    @Test
    void otherRolesAreForbidden() {
        for (UserRole role : new UserRole[]{UserRole.MANAGER, UserRole.DISPATCHER, UserRole.SYSTEM_MANAGER,
                UserRole.SYSTEM_SUPPORTER, UserRole.DRIVER, UserRole.WAREHOUSE_WORKER}) {
            assertThatThrownBy(() -> AuditLogScope.of(role, 1L, null))
                    .isInstanceOfSatisfying(AppException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED));
        }
    }
}
