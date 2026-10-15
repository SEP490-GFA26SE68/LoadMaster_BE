package fu.se184491.loadmaster_be.dto.response.account;

public record ResetPasswordResponse(
        Long userId,
        UserListItemResponse user,
        String temporaryPassword
) {
}
