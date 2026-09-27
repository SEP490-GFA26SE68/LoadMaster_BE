package fu.se184491.loadmaster_be.dto.response.account;

import lombok.Builder;

@Builder
public record CreateUserResponse(
        Long id,
        String keycloakId,
        String email,
        String phoneNumber,
        String fullName,
        String role
) {
}