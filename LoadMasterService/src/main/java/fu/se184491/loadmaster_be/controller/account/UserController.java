package fu.se184491.loadmaster_be.controller.account;

import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.account.UserStatus;
import fu.se184491.loadmaster_be.dto.ApiResponse;
import fu.se184491.loadmaster_be.dto.PageResponse;
import fu.se184491.loadmaster_be.dto.request.account.CreateUserRequest;
import fu.se184491.loadmaster_be.dto.request.account.UpdateUserRequest;
import fu.se184491.loadmaster_be.dto.request.account.UpdateUserStatusRequest;
import fu.se184491.loadmaster_be.dto.response.account.CreateUserResponse;
import fu.se184491.loadmaster_be.dto.response.account.ResetPasswordResponse;
import fu.se184491.loadmaster_be.dto.response.account.UserListItemResponse;
import fu.se184491.loadmaster_be.dto.response.account.UserProfileResponse;
import fu.se184491.loadmaster_be.dto.response.account.UserStatsResponse;
import fu.se184491.loadmaster_be.service.account.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {
    private final UserService userService;

    @GetMapping("/me")
    public ApiResponse<UserProfileResponse> getCurrentUser(
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ApiResponse.success(
                userService.getUserProfile(jwt)
        );
    }

    @PostMapping
    @PreAuthorize("hasAuthority('USERS_CREATE')")
    public ResponseEntity<ApiResponse<CreateUserResponse>> createUser(
            @Valid @RequestBody CreateUserRequest request
    ) {
        CreateUserResponse response =
                userService.createUser(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(
                        ApiResponse.success(
                                "User created successfully",
                                response
                        )
                );
    }

    @PreAuthorize("hasAuthority('USERS_READ') or hasAnyRole('SYSTEM_MANAGER', 'SYSTEM_ADMIN')")
    @GetMapping
    public PageResponse<UserListItemResponse> getUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) UserRole role,
            @RequestParam(required = false) UserStatus status,
            @RequestParam(required = false) Long companyId
    ) {
        return userService.getUsers(
                page,
                size,
                search,
                role,
                status,
                companyId
        );
    }

    @PreAuthorize("hasAuthority('USERS_READ') or hasAnyRole('SYSTEM_MANAGER', 'SYSTEM_ADMIN')")
    @GetMapping("/stats")
    public ApiResponse<UserStatsResponse> getUserStats() {
        return ApiResponse.success(
                "User statistics retrieved successfully",
                userService.getUserStats()
        );
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('USERS_UPDATE') or hasAnyRole('SYSTEM_MANAGER', 'SYSTEM_ADMIN')")
    public ApiResponse<UserListItemResponse> updateUser(
            @PathVariable Long id,
            @Valid @RequestBody UpdateUserRequest request
    ) {
        return ApiResponse.success(
                "User updated successfully",
                userService.updateUser(id, request)
        );
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAuthority('USERS_LOCK') or hasAnyRole('SYSTEM_MANAGER', 'SYSTEM_ADMIN')")
    public ApiResponse<UserListItemResponse> updateUserStatus(
            @PathVariable Long id,
            @Valid @RequestBody UpdateUserStatusRequest request
    ) {
        return ApiResponse.success(
                "User status updated successfully",
                userService.updateUserStatus(id, request)
        );
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('USERS_DELETE')")
    public ApiResponse<Void> deleteUser(
            @PathVariable Long id
    ) {
        userService.deleteUser(id);
        return ApiResponse.success(
                "User deleted successfully",
                null
        );
    }

    @PostMapping("/{id}/reset-password")
    @PreAuthorize("hasAuthority('USERS_UPDATE')")
    public ApiResponse<ResetPasswordResponse> resetPassword(
            @PathVariable Long id
    ) {
        return ApiResponse.success(
                "Password reset successfully",
                userService.resetPassword(id)
        );
    }

}
