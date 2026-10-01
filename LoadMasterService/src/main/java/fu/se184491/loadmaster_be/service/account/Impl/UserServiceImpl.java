package fu.se184491.loadmaster_be.service.account.Impl;

import fu.se184491.loadmaster_be.client.KeycloakAdminClient;
import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.account.UserStatus;
import fu.se184491.loadmaster_be.dto.request.account.CreateUserRequest;
import fu.se184491.loadmaster_be.dto.response.account.CreateUserResponse;
import fu.se184491.loadmaster_be.dto.response.account.UserProfileResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import fu.se184491.loadmaster_be.repository.company.CompanyRepository;
import fu.se184491.loadmaster_be.service.account.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserServiceImpl implements UserService {
    private final UserRepository userRepository;
    private final CompanyRepository companyRepository;
    private final KeycloakAdminClient keycloakAdminClient;

    public UserProfileResponse getUserProfile(Jwt jwt) {

        String keycloakId = jwt.getSubject();

        User user = userRepository.findByKeycloakId(keycloakId)
                .orElseThrow(() ->
                        new AppException(ErrorCode.USER_NOT_FOUND)
                );

        return UserProfileResponse.builder()
                .id(user.getId())
                .keycloakId(user.getKeycloakId())
                .username(jwt.getClaimAsString("preferred_username"))
                .email(user.getEmail())
                .phoneNumber(user.getPhoneNumber())
                .fullName(user.getFullName())
                .companyId(
                        user.getCompany() != null
                                ? user.getCompany().getId()
                                : null
                )
                .status(user.getStatus())
                .build();
    }

    @Transactional
    public CreateUserResponse createUser(CreateUserRequest request) {

        if (userRepository.existsByEmail(request.email())) {
            throw new AppException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }

        Company company = companyRepository
                .findById(request.companyId())
                .orElseThrow(() ->
                        new AppException(ErrorCode.COMPANY_NOT_FOUND)
                );

        String keycloakUserId = null;

        try {
            keycloakUserId =
                    keycloakAdminClient.createUser(
                            request.email(),
                            request.fullName()
                    );

            keycloakAdminClient.setPassword(
                    keycloakUserId,
                    request.password()
            );

            keycloakAdminClient.assignRealmRole(
                    keycloakUserId,
                    request.role().name()
            );

            User user = User.builder()
                    .keycloakId(keycloakUserId)
                    .company(company)
                    .email(request.email())
                    .fullName(request.fullName())
                    .phoneNumber(request.phoneNumber())
                    .userRoleType(request.role())
                    .status(UserStatus.ACTIVE)
                    .build();

            user = userRepository.save(user);

            return CreateUserResponse.builder()
                    .id(user.getId())
                    .keycloakId(user.getKeycloakId())
                    .email(user.getEmail())
                    .phoneNumber(user.getPhoneNumber())
                    .fullName(user.getFullName())
                    .role(request.role().name())
                    .build();

        } catch (Exception ex) {

            if (keycloakUserId != null) {
                try {
                    keycloakAdminClient.deleteUser(keycloakUserId);
                } catch (Exception ignored) {
                }
            }

            throw ex;
        }
    }
}
