package fu.se184491.loadmaster_be.dto.response.account;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.account.UserStatus;
import fu.se184491.loadmaster_be.constant.account.UserType;
import fu.se184491.loadmaster_be.constant.company.CompanyStatus;
import lombok.Builder;

@Builder
public record UserProfileResponse(
        Long id,
        String keycloakId,
        String username,
        String email,
        String fullName,
        String phoneNumber,
        Long companyId,
        String companyName,
        String companyCode,
        String companyTaxCode,
        String companyBillingEmail,
        CompanyStatus companyStatus,

        UserRole userRoleType,
        UserType userType,
        UserStatus status,
        Boolean subscriptionExpired
) {
}