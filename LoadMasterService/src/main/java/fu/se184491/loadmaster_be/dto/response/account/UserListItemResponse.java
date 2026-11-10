package fu.se184491.loadmaster_be.dto.response.account;

import com.fasterxml.jackson.annotation.JsonFormat;
import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.account.UserStatus;
import fu.se184491.loadmaster_be.constant.account.UserType;

import java.time.LocalDateTime;

public record UserListItemResponse(
        Long id,
        String keycloakId,
        String email,
        String fullName,
        String phoneNumber,
        UserRole userRoleType,
        UserType userType,
        UserStatus status,
        Long companyId,
        String companyName,
        String companyCode,
        String taxCode,
        String planName,
        String planTier,
        String planCode,
        String subscriptionStatus,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime subscriptionExpiresAt,
        Integer creditBalance,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime createdAt
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
        this(id, keycloakId, email, fullName, phoneNumber, userRoleType, UserType.OFFICIAL, status,
                companyId, companyName, null, null, null, null, null, null, null, null, null);
    }
}
