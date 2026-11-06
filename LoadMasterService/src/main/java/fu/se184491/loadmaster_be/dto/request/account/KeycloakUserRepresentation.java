package fu.se184491.loadmaster_be.dto.request.account;

public record KeycloakUserRepresentation(
        String id,
        String username,
        String email,
        String firstName,
        String lastName,
        Boolean enabled,
        String serviceAccountClientId
) {
}