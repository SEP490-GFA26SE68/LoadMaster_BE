package fu.se184491.loadmaster_be.dto.response.account;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.account.UserStatus;

import java.time.Instant;
import java.time.LocalDateTime;

public record UserListItemResponse(
        Long id,
        String keycloakId,
        String email,
        String fullName,
        String phoneNumber,
        UserRole userRoleType,
        UserStatus status,
        Long companyId,
        String companyName,
        String companyCode,
        String taxCode,
        String planName,
        String planTier,
        String planCode,
        String subscriptionStatus,
        LocalDateTime subscriptionExpiresAt,
        Integer creditBalance,
        Instant createdAt
) {
    public UserListItemResponse(
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
        this(id, keycloakId, email, fullName, phoneNumber, userRoleType, status,
                companyId, companyName, null, null, null, null, null, null, null, null, null);
    }
}