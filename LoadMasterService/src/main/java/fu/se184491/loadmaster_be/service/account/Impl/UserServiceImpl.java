package fu.se184491.loadmaster_be.service.account.Impl;

import fu.se184491.loadmaster_be.client.KeycloakAdminClient;
import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.account.UserStatus;
import fu.se184491.loadmaster_be.dto.PageResponse;
import fu.se184491.loadmaster_be.dto.request.account.CreateUserRequest;
import fu.se184491.loadmaster_be.dto.request.account.UpdateUserRequest;
import fu.se184491.loadmaster_be.dto.request.account.UpdateUserStatusRequest;
import fu.se184491.loadmaster_be.dto.response.account.CreateUserResponse;
import fu.se184491.loadmaster_be.dto.response.account.ResetPasswordResponse;
import fu.se184491.loadmaster_be.dto.response.account.UserListItemResponse;
import fu.se184491.loadmaster_be.dto.response.account.UserProfileResponse;
import fu.se184491.loadmaster_be.dto.response.account.UserStatsResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.helpers.TemporaryPasswordGenerator;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import fu.se184491.loadmaster_be.repository.company.CompanyRepository;
import fu.se184491.loadmaster_be.service.account.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserServiceImpl implements UserService {
    private final UserRepository userRepository;
    private final CompanyRepository companyRepository;
    private final KeycloakAdminClient keycloakAdminClient;
    private final CurrentUserService currentUserService;
    private final TemporaryPasswordGenerator temporaryPasswordGenerator;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private fu.se184491.loadmaster_be.repository.billing.CompanySubscriptionRepository companySubscriptionRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private fu.se184491.loadmaster_be.repository.billing.CreditAccountRepository creditAccountRepository;

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
                .userRoleType(user.getUserRoleType())
                .status(user.getStatus())
                .build();
    }

    @Transactional(readOnly = true)
    public PageResponse<UserListItemResponse> getUsers(
            int page,
            int size,
            String search,
            UserRole role,
            UserStatus status,
            Long companyId
    ) {
        // 1. Current user
        User currentUser =
                currentUserService.getCurrentUser();

        // 2. Pagination + sort
        Pageable pageable = PageRequest.of(
                page,
                size,
                Sort.by(
                        Sort.Direction.DESC,
                        "createdAt"
                )
        );

        Specification<User> spec =
                Specification.unrestricted();

        // 3. Scope theo role
        if (currentUser.getUserRoleType() == UserRole.SYSTEM_ADMIN
                || currentUser.getUserRoleType() == UserRole.SYSTEM_MANAGER) {

            // SYSTEM_ADMIN / SYSTEM_MANAGER được filter theo company bất kỳ
            if (companyId != null) {
                spec = spec.and(
                        (root, query, cb) ->
                                cb.equal(
                                        root.get("company").get("id"),
                                        companyId
                                )
                );
            }

        } else if (currentUser.getUserRoleType() == UserRole.ADMIN) {

            Long ownCompanyId =
                    currentUserService.getCurrentCompanyId();

            // ADMIN luôn bị ép scope về company của chính mình
            spec = spec.and(
                    (root, query, cb) ->
                            cb.equal(
                                    root.get("company").get("id"),
                                    ownCompanyId
                            )
            );

        } else {
            // Nếu vào được đây thì RolePermissionMapping đang cấu hình sai,
            // vì role này có USERS_READ nhưng service không biết scope thế nào.
            throw new IllegalStateException(
                    "USERS_READ permission is assigned to an unsupported role: "
                            + currentUser.getUserRoleType()
            );
        }

        // 4. Search
        if (search != null && !search.isBlank()) {
            String keyword =
                    "%" + search.trim().toLowerCase() + "%";

            spec = spec.and(
                    (root, query, cb) ->
                            cb.or(
                                    cb.like(
                                            cb.lower(
                                                    root.get("fullName")
                                            ),
                                            keyword
                                    ),
                                    cb.like(
                                            cb.lower(
                                                    root.get("email")
                                            ),
                                            keyword
                                    ),
                                    cb.like(
                                            cb.lower(
                                                    root.get("phoneNumber")
                                            ),
                                            keyword
                                    )
                            )
            );
        }

        // 5. Role filter
        if (role != null) {
            spec = spec.and(
                    (root, query, cb) ->
                            cb.equal(
                                    root.get("userRoleType"),
                                    role
                            )
            );
        }

        // 6. Status filter
        if (status != null) {
            spec = spec.and(
                    (root, query, cb) ->
                            cb.equal(
                                    root.get("status"),
                                    status
                            )
            );
        }

        // 7. Query
        Page<UserListItemResponse> result =
                userRepository
                        .findAll(spec, pageable)
                        .map(this::toUserListItemResponse);

        return PageResponse.of(
                "Users retrieved successfully",
                result
        );
    }

    @Transactional
    public CreateUserResponse createUser(CreateUserRequest request) {
        User currentUser = currentUserService.getCurrentUser();
        Long targetCompanyId = resolveAndValidateTargetCompanyId(currentUser, request);

        if (userRepository.existsByEmail(request.email())) {
            throw new AppException(
                    ErrorCode.EMAIL_ALREADY_EXISTS
            );
        }

        Company company = null;

        if (!isPlatformRole(request.role())) {

            if (targetCompanyId == null) {
                throw new AppException(
                        ErrorCode.COMPANY_REQUIRED
                );
            }

            company = companyRepository
                    .findById(targetCompanyId)
                    .orElseThrow(() ->
                            new AppException(
                                    ErrorCode.COMPANY_NOT_FOUND
                            )
                    );
        }

        String temporaryPassword =
                temporaryPasswordGenerator.generate();

        String keycloakUserId = null;

        try {
            keycloakUserId =
                    keycloakAdminClient.createUser(
                            request.email(),
                            request.fullName()
                    );

            keycloakAdminClient.setPassword(
                    keycloakUserId,
                    temporaryPassword
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

            return new CreateUserResponse(
                    toUserListItemResponse(user),
                    temporaryPassword
            );

        } catch (Exception ex) {

            if (keycloakUserId != null) {
                try {
                    keycloakAdminClient.deleteUser(
                            keycloakUserId
                    );
                } catch (Exception ignored) {
                }
            }

            throw ex;
        }
    }

    private Long resolveAndValidateTargetCompanyId(User currentUser, CreateUserRequest request) {
        UserRole callerRole = currentUser.getUserRoleType();

        if (callerRole == UserRole.ADMIN) {
            if (currentUser.getCompany() == null) {
                throw new AppException(ErrorCode.USER_COMPANY_NOT_FOUND);
            }
            Long callerCompanyId = currentUser.getCompany().getId();
            if (isPlatformRole(request.role()) || request.role() == UserRole.ADMIN) {
                throw new AppException(
                        ErrorCode.UNAUTHORIZED,
                        "Tài khoản ADMIN công ty chỉ được phép tạo các vai trò nội bộ: [MANAGER, DISPATCHER, WAREHOUSE_WORKER, DRIVER]"
                );
            }
            if (request.companyId() != null && !request.companyId().equals(callerCompanyId)) {
                throw new AppException(
                        ErrorCode.UNAUTHORIZED,
                        "Tài khoản ADMIN công ty chỉ được phép tạo nhân viên thuộc công ty của mình"
                );
            }
            return callerCompanyId;
        }

        if (callerRole == UserRole.SYSTEM_MANAGER) {
            if (isPlatformRole(request.role())) {
                throw new AppException(
                        ErrorCode.UNAUTHORIZED,
                        "Tài khoản SYSTEM_MANAGER chỉ được phép tạo các vai trò cấp công ty: [ADMIN, MANAGER, DISPATCHER, WAREHOUSE_WORKER, DRIVER]"
                );
            }
            return request.companyId();
        }

        if (callerRole == UserRole.SYSTEM_ADMIN) {
            return request.companyId();
        }

        throw new AppException(ErrorCode.UNAUTHORIZED);
    }

    @Transactional(readOnly = true)
    public UserStatsResponse getUserStats() {

        User currentUser =
                currentUserService.getCurrentUser();

        Specification<User> scope =
                Specification.unrestricted();

        if (currentUser.getUserRoleType() == UserRole.SYSTEM_ADMIN
                || currentUser.getUserRoleType() == UserRole.SYSTEM_MANAGER) {

            // toàn hệ thống

        } else if (currentUser.getUserRoleType() == UserRole.ADMIN) {

            Long companyId =
                    currentUserService.getCurrentCompanyId();

            scope = scope.and(
                    (root, query, cb) ->
                            cb.equal(
                                    root.get("company").get("id"),
                                    companyId
                            )
            );

        } else {
            throw new IllegalStateException(
                    "USERS_READ permission is assigned to an unsupported role: "
                            + currentUser.getUserRoleType()
            );
        }

        long totalUsers =
                userRepository.count(scope);

        long activeUsers =
                userRepository.count(
                        scope.and(
                                (root, query, cb) ->
                                        cb.equal(
                                                root.get("status"),
                                                UserStatus.ACTIVE
                                        )
                        )
                );

        long lockedUsers =
                userRepository.count(
                        scope.and(
                                (root, query, cb) ->
                                        cb.equal(
                                                root.get("status"),
                                                UserStatus.LOCKED
                                        )
                        )
                );

        return new UserStatsResponse(
                totalUsers,
                activeUsers,
                lockedUsers
        );
    }

    @Override
    @Transactional
    public UserListItemResponse updateUser(Long id, UpdateUserRequest request) {
        User currentUser = currentUserService.getCurrentUser();
        User targetUser = getScopedTargetUser(currentUser, id);

        targetUser.setFullName(request.fullName());
        targetUser.setPhoneNumber(request.phoneNumber());

        if (request.role() != null) {
            UserRole callerRole = currentUser.getUserRoleType();
            if (callerRole == UserRole.ADMIN
                    && (isPlatformRole(request.role()) || request.role() == UserRole.ADMIN)) {
                throw new AppException(
                        ErrorCode.UNAUTHORIZED,
                        "Tài khoản ADMIN công ty chỉ được phép gán các vai trò nội bộ: [MANAGER, DISPATCHER, WAREHOUSE_WORKER, DRIVER]"
                );
            }
            if (callerRole == UserRole.SYSTEM_MANAGER && isPlatformRole(request.role())) {
                throw new AppException(
                        ErrorCode.UNAUTHORIZED,
                        "Tài khoản SYSTEM_MANAGER chỉ được phép gán các vai trò cấp công ty: [ADMIN, MANAGER, DISPATCHER, WAREHOUSE_WORKER, DRIVER]"
                );
            }
            targetUser.setUserRoleType(request.role());
        }

        if (request.companyId() != null) {
            UserRole callerRole = currentUser.getUserRoleType();
            if (callerRole != UserRole.SYSTEM_MANAGER && callerRole != UserRole.SYSTEM_ADMIN) {
                throw new AppException(
                        ErrorCode.UNAUTHORIZED,
                        "Chỉ tài khoản quản lý hệ thống / quản lý nền tảng mới được phép thay đổi công ty của người dùng"
                );
            }
            fu.se184491.loadmaster_be.entity.company.Company targetCompany = companyRepository.findById(request.companyId())
                    .orElseThrow(() -> new AppException(ErrorCode.COMPANY_NOT_FOUND));
            targetUser.setCompany(targetCompany);
        }

        User saved = userRepository.save(targetUser);
        return toUserListItemResponse(saved);
    }

    @Override
    @Transactional
    public UserListItemResponse updateUserStatus(Long id, UpdateUserStatusRequest request) {
        User currentUser = currentUserService.getCurrentUser();
        if (currentUser.getId() != null && currentUser.getId().equals(id)) {
            throw new AppException(ErrorCode.INVALID_INPUT);
        }

        User targetUser = getScopedTargetUser(currentUser, id);
        targetUser.setStatus(request.status());

        User saved = userRepository.save(targetUser);
        return toUserListItemResponse(saved);
    }

    @Override
    @Transactional
    public void deleteUser(Long id) {
        User currentUser = currentUserService.getCurrentUser();
        if (currentUser.getId() != null && currentUser.getId().equals(id)) {
            throw new AppException(ErrorCode.INVALID_INPUT);
        }

        User targetUser = getScopedTargetUser(currentUser, id);
        if (targetUser.getKeycloakId() != null && !targetUser.getKeycloakId().isBlank()) {
            try {
                keycloakAdminClient.deleteUser(targetUser.getKeycloakId());
            } catch (HttpClientErrorException.NotFound ex) {
                log.warn("Keycloak user {} already absent when deleting user id={}", targetUser.getKeycloakId(), id);
            }
        }
        userRepository.delete(targetUser);
    }

    @Override
    @Transactional
    public ResetPasswordResponse resetPassword(Long id) {
        User currentUser = currentUserService.getCurrentUser();
        User targetUser = getScopedTargetUser(currentUser, id);

        String temporaryPassword = temporaryPasswordGenerator.generate();
        keycloakAdminClient.setPassword(targetUser.getKeycloakId(), temporaryPassword);

        return new ResetPasswordResponse(
                targetUser.getId(),
                toUserListItemResponse(targetUser),
                temporaryPassword
        );
    }

    private User getScopedTargetUser(User currentUser, Long targetUserId) {
        User targetUser = userRepository.findById(targetUserId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));

        UserRole callerRole = currentUser.getUserRoleType();
        if (callerRole == UserRole.SYSTEM_ADMIN || callerRole == UserRole.SYSTEM_MANAGER) {
            return targetUser;
        }

        if (callerRole == UserRole.ADMIN) {
            if (currentUser.getCompany() == null
                    || targetUser.getCompany() == null
                    || !targetUser.getCompany().getId().equals(currentUser.getCompany().getId())) {
                throw new AppException(ErrorCode.UNAUTHORIZED);
            }
            return targetUser;
        }

        throw new AppException(ErrorCode.UNAUTHORIZED);
    }

    private boolean isPlatformRole(UserRole role) {
        return role == UserRole.SYSTEM_ADMIN
                || role == UserRole.SYSTEM_MANAGER
                || role == UserRole.SYSTEM_SUPPORTER;
    }

    private UserListItemResponse toUserListItemResponse(
            User user
    ) {
        String companyCode = null;
        String taxCode = null;
        String planName = null;
        String planTier = null;
        String planCode = null;
        String subscriptionStatus = null;
        java.time.LocalDateTime subscriptionExpiresAt = null;
        Integer creditBalance = null;

        if (user.getCompany() != null) {
            companyCode = user.getCompany().getCompanyCode();
            taxCode = user.getCompany().getTaxCode();
            Long companyId = user.getCompany().getId();

            if (companySubscriptionRepository != null && companyId != null) {
                var subOpt = companySubscriptionRepository.findByCompanyId(companyId);
                if (subOpt.isPresent()) {
                    var sub = subOpt.get();
                    if (sub.getPlan() != null) {
                        planName = sub.getPlan().getName();
                        planCode = sub.getPlan().getPlanCode();
                        planTier = sub.getPlan().getTier() != null ? sub.getPlan().getTier().name() : null;
                    }
                    if (sub.getStatus() != null) {
                        subscriptionStatus = sub.getStatus().name();
                    }
                    subscriptionExpiresAt = sub.getExpiresAt();
                }
            }

            if (creditAccountRepository != null && companyId != null) {
                var creditOpt = creditAccountRepository.findByCompanyId(companyId);
                if (creditOpt.isPresent()) {
                    creditBalance = creditOpt.get().getBalance();
                }
            }
        }

        return new UserListItemResponse(
                user.getId(),
                user.getKeycloakId(),
                user.getEmail(),
                user.getFullName(),
                user.getPhoneNumber(),
                user.getUserRoleType(),
                user.getStatus(),
                user.getCompany() != null
                        ? user.getCompany().getId()
                        : null,
                user.getCompany() != null
                        ? user.getCompany().getCompanyName()
                        : null,
                companyCode,
                taxCode,
                planName,
                planTier,
                planCode,
                subscriptionStatus,
                subscriptionExpiresAt,
                creditBalance,
                user.getCreatedAt()
        );
    }
}
