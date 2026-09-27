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

@Component
@RequiredArgsConstructor
public class CurrentUserService {

    private final UserRepository userRepository;

    public User getCurrentUser() {
        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();

        if (!(authentication instanceof JwtAuthenticationToken jwtAuth)) {
            throw new IllegalStateException("Current user is not authenticated");
        }

        String keycloakId = jwtAuth.getToken().getSubject();

        return userRepository
                .findByKeycloakId(keycloakId)
                .orElseThrow(() ->
                        new AppException(ErrorCode.USER_PROFILE_NOT_FOUND)
                );
    }

    public Long getCurrentCompanyId() {
        User user = getCurrentUser();

        if (user.getCompany() == null) {
            throw new IllegalStateException("User does not belong to a company");
        }

        return user.getCompany().getId();
    }
}