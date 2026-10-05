package fu.se184491.loadmaster_be.service.account;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.account.UserStatus;
import fu.se184491.loadmaster_be.dto.PageResponse;
import fu.se184491.loadmaster_be.dto.request.account.CreateUserRequest;
import fu.se184491.loadmaster_be.dto.response.account.CreateUserResponse;
import fu.se184491.loadmaster_be.dto.response.account.UserListItemResponse;
import fu.se184491.loadmaster_be.dto.response.account.UserProfileResponse;
import fu.se184491.loadmaster_be.dto.response.account.UserStatsResponse;
import org.springframework.security.oauth2.jwt.Jwt;

public interface UserService {
    UserProfileResponse getUserProfile(Jwt jwt);

    PageResponse<UserListItemResponse> getUsers(
            int page,
            int size,
            String search,
            UserRole role,
            UserStatus status,
            Long companyId
    );

    CreateUserResponse createUser(CreateUserRequest request);

    UserStatsResponse getUserStats();
}
