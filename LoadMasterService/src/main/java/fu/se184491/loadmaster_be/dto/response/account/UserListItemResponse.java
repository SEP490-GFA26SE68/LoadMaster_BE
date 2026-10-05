package fu.se184491.loadmaster_be.dto.response.account;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.account.UserStatus;

public record UserListItemResponse(
        Long id,
        String keycloakId,
        String email,
        String fullName,
        String phoneNumber,
        UserRole userRoleType,
        UserStatus status,
        Long companyId,
        String companyName
) {
}