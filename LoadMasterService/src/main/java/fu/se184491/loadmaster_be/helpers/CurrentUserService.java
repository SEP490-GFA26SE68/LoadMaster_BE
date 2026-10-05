package fu.se184491.loadmaster_be.helpers;

import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
@RequiredArgsConstructor
public class CurrentUserService {

    private final UserRepository userRepository;

    public User getCurrentUser() {
        Authentication authentication =
                SecurityContextHolder
                        .getContext()
                        .getAuthentication();

        // Trường hợp này về lý thuyết đã bị Spring Security chặn trước.
        // Nếu vẫn lọt vào đây thì coi là trạng thái hệ thống bất thường.
        if (!(authentication instanceof JwtAuthenticationToken jwtAuth)) {
            throw new IllegalStateException(
                    "Authenticated JWT principal is required"
            );
        }

        String keycloakId =
                jwtAuth.getToken().getSubject();

        return userRepository
                .findByKeycloakId(keycloakId)
                .orElseThrow(() ->
                        new AppException(
                                ErrorCode.USER_PROFILE_NOT_FOUND
                        )
                );
    }

    public boolean hasRole(String role) {
        Authentication authentication =
                SecurityContextHolder
                        .getContext()
                        .getAuthentication();

        if (authentication == null) {
            return false;
        }

        return authentication
                .getAuthorities()
                .stream()
                .anyMatch(authority ->
                        Objects.equals(
                                authority.getAuthority(),
                                "ROLE_" + role
                        )
                );
    }

    public Long getCurrentCompanyId() {
        User user = getCurrentUser();

        if (user.getCompany() == null) {
            throw new AppException(
                    ErrorCode.USER_COMPANY_NOT_FOUND
            );
        }

        return user.getCompany().getId();
    }
}