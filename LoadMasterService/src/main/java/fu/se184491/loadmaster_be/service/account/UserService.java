package fu.se184491.loadmaster_be.service.account;

import fu.se184491.loadmaster_be.dto.request.account.CreateUserRequest;
import fu.se184491.loadmaster_be.dto.response.account.CreateUserResponse;
import fu.se184491.loadmaster_be.dto.response.account.UserProfileResponse;
import org.springframework.security.oauth2.jwt.Jwt;

public interface UserService {
    UserProfileResponse getUserProfile(Jwt jwt);

    CreateUserResponse createUser(CreateUserRequest request);
}
