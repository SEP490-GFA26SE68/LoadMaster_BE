package fu.se184491.loadmaster_be.dto.response.account;

import lombok.Builder;

@Builder
public record CreateUserResponse(
        UserListItemResponse user,
        String temporaryPassword
) {
}