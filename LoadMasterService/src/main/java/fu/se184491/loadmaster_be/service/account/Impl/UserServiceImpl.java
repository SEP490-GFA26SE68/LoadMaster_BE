package fu.se184491.loadmaster_be.service.account.Impl;

import fu.se184491.loadmaster_be.client.BrevoClient;
import fu.se184491.loadmaster_be.client.KeycloakAdminClient;
import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.account.UserStatus;
import fu.se184491.loadmaster_be.constant.company.AgreementStatus;
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
import fu.se184491.loadmaster_be.entity.company.CompanyAgreement;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.helpers.TemporaryPasswordGenerator;
import fu.se184491.loadmaster_be.entity.common.AuditLog;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import fu.se184491.loadmaster_be.repository.common.AuditLogRepository;
import fu.se184491.loadmaster_be.service.audit.AuditCompany;
import fu.se184491.loadmaster_be.repository.company.CompanyAgreementRepository;
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

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserServiceImpl implements UserService {
    private final UserRepository userRepository;
    private final CompanyRepository companyRepository;
    private final KeycloakAdminClient keycloakAdminClient;
    private final CurrentUserService currentUserService;
    private final TemporaryPasswordGenerator temporaryPasswordGenerator;
    private final CompanyAgreementRepository companyAgreementRepository;
    private final BrevoClient brevoClient;
    private final AuditLogRepository auditLogRepository;

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
                .userType(user.getUserType() != null ? user.getUserType() : fu.se184491.loadmaster_be.constant.account.UserType.OFFICIAL)
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

        String targetEmail = request.email();
        String targetFullName = request.fullName();
        String targetPhone = request.phoneNumber();

        if (currentUser.getUserRoleType() == UserRole.SYSTEM_ADMIN
                && request.role() == UserRole.ADMIN
                && company != null
                && companyAgreementRepository != null) {
            Optional<CompanyAgreement> agreementOpt =
                    companyAgreementRepository.findFirstByCompanyIdOrderByCreatedAtDesc(company.getId());
            if (agreementOpt.isPresent()) {
                CompanyAgreement agreement = agreementOpt.get();
                if (agreement.getStatus() != AgreementStatus.SIGNED) {
                    throw new AppException(
                            ErrorCode.INVALID_INPUT,
                            "Doanh nghiệp chưa ký thỏa thuận dịch vụ. Không thể cấp tài khoản ADMIN công ty."
                    );
                }
                if (agreement.getSignerEmail() != null && !agreement.getSignerEmail().isBlank()) {
                    targetEmail = agreement.getSignerEmail().trim();
                }
                if (agreement.getSignerName() != null && !agreement.getSignerName().isBlank()) {
                    targetFullName = agreement.getSignerName().trim();
                }
                if (agreement.getSignerPhone() != null && !agreement.getSignerPhone().isBlank()) {
                    targetPhone = agreement.getSignerPhone().trim();
                }
            }
        }

        // Nếu người gọi là Company Admin (ADMIN), bắt buộc công ty phải ký thỏa thuận dịch vụ trước khi tạo nhân viên
        if (currentUser.getUserRoleType() == UserRole.ADMIN
                && company != null
                && companyAgreementRepository != null) {
            Optional<CompanyAgreement> agreementOpt =
                    companyAgreementRepository.findFirstByCompanyIdOrderByCreatedAtDesc(company.getId());
            if (agreementOpt.isPresent()) {
                CompanyAgreement agreement = agreementOpt.get();
                if (agreement.getStatus() != AgreementStatus.SIGNED) {
                    throw new AppException(
                            ErrorCode.AGREEMENT_REQUIRED,
                            "Vui lòng hoàn tất ký thỏa thuận dịch vụ thử nghiệm trước khi tạo tài khoản nhân viên."
                    );
                }
            }
        }

        if (userRepository.existsByEmail(targetEmail)) {
            throw new AppException(
                    ErrorCode.EMAIL_ALREADY_EXISTS
            );
        }

        String temporaryPassword =
                temporaryPasswordGenerator.generate();

        String keycloakUserId = null;

        try {
            keycloakUserId =
                    keycloakAdminClient.createUser(
                            targetEmail,
                            targetFullName
                    );

            keycloakAdminClient.setPassword(
                    keycloakUserId,
                    temporaryPassword,
                    true
            );

            keycloakAdminClient.assignRealmRole(
                    keycloakUserId,
                    request.role().name()
            );

            User user = User.builder()
                    .keycloakId(keycloakUserId)
                    .company(company)
                    .email(targetEmail)
                    .fullName(targetFullName)
                    .phoneNumber(targetPhone)
                    .userRoleType(request.role())
                    .status(UserStatus.ACTIVE)
                    .build();
            user.prePersist();

            user = userRepository.save(user);

            if (currentUser.getUserRoleType() == UserRole.SYSTEM_ADMIN
                    && user.getUserRoleType() == UserRole.ADMIN
                    && company != null
                    && brevoClient != null) {
                String subject = "LoadMaster - Thông tin tài khoản Quản trị viên Doanh nghiệp (Company Admin)";
                String htmlContent = buildCompanyAdminProvisionEmailHtml(company, user, temporaryPassword);
                brevoClient.sendEmail(user.getEmail(), subject, htmlContent);
            }

            logAccount(ACTION_USER_CREATED, currentUser, user, null, accountSnapshot(user));

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

    private String buildCompanyAdminProvisionEmailHtml(Company company, User adminUser, String temporaryPassword) {
        return """
                <div style="font-family: Arial, sans-serif; line-height: 1.6; color: #1e293b;">
                    <h2>Chào mừng Doanh nghiệp %s đến với LoadMaster!</h2>
                    <p>Xin chào <strong>%s</strong>,</p>
                    <p>Sau khi hoàn tất ký thỏa thuận dịch vụ, Quản trị viên Hệ thống (System Admin) đã khởi tạo tài khoản <strong>Quản trị viên Doanh nghiệp (ADMIN)</strong> cho công ty của bạn:</p>
                    <ul>
                        <li><strong>Email đăng nhập:</strong> %s</li>
                        <li><strong>Mật khẩu tạm thời:</strong> <code>%s</code></li>
                        <li><strong>Vai trò:</strong> ADMIN (Quản trị viên Công ty)</li>
                    </ul>
                    <p>Vui lòng đăng nhập và đổi mật khẩu ngay trong lần sử dụng đầu tiên. Các tài khoản nhân sự nội bộ trong công ty (MANAGER, DISPATCHER, WAREHOUSE_WORKER, DRIVER) sẽ do chính tài khoản ADMIN công ty của bạn khởi tạo và quản lý.</p>
                </div>
                """.formatted(
                company.getCompanyName() != null ? company.getCompanyName() : "",
                adminUser.getFullName() != null ? adminUser.getFullName() : adminUser.getEmail(),
                adminUser.getEmail(),
                temporaryPassword
        );
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
            throw new AppException(
                    ErrorCode.UNAUTHORIZED,
                    "Chỉ tài khoản SYSTEM_ADMIN mới được phép tạo tài khoản quản trị doanh nghiệp (ADMIN)"
            );
        }

        if (callerRole == UserRole.SYSTEM_ADMIN) {
            if (!isPlatformRole(request.role()) && request.role() != UserRole.ADMIN) {
                throw new AppException(
                        ErrorCode.UNAUTHORIZED,
                        "Tài khoản SYSTEM_ADMIN chỉ được phép tạo vai trò ADMIN cho công ty khách hàng (các vai trò nhỏ trong công ty do ADMIN công ty tự tạo)"
                );
            }
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
        UserRole callerRole = currentUser.getUserRoleType();
        UserRole targetRole = targetUser.getUserRoleType();

        if (callerRole == UserRole.SYSTEM_ADMIN) {
            if (!isPlatformRole(targetRole) && targetRole != UserRole.ADMIN) {
                throw new AppException(
                        ErrorCode.UNAUTHORIZED,
                        "Tài khoản quản trị hệ thống không được phép chỉnh sửa các vai trò nội bộ của công ty: [MANAGER, DISPATCHER, WAREHOUSE_WORKER, DRIVER]"
                );
            }
        } else if (callerRole == UserRole.ADMIN) {
            if (isPlatformRole(targetRole) || targetRole == UserRole.ADMIN) {
                throw new AppException(
                        ErrorCode.UNAUTHORIZED,
                        "Tài khoản ADMIN công ty chỉ được phép chỉnh sửa các vai trò nội bộ trong cùng công ty: [MANAGER, DISPATCHER, WAREHOUSE_WORKER, DRIVER]"
                );
            }
        } else {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }

        Map<String, Object> before = accountSnapshot(targetUser);
        targetUser.setFullName(request.fullName());
        targetUser.setPhoneNumber(request.phoneNumber());

        if (request.role() != null) {
            if (callerRole == UserRole.ADMIN
                    && (isPlatformRole(request.role()) || request.role() == UserRole.ADMIN)) {
                throw new AppException(
                        ErrorCode.UNAUTHORIZED,
                        "Tài khoản ADMIN công ty chỉ được phép gán các vai trò nội bộ: [MANAGER, DISPATCHER, WAREHOUSE_WORKER, DRIVER]"
                );
            }
            if (callerRole == UserRole.SYSTEM_ADMIN
                    && !isPlatformRole(request.role())
                    && request.role() != UserRole.ADMIN) {
                throw new AppException(
                        ErrorCode.UNAUTHORIZED,
                        "Tài khoản quản trị hệ thống không được phép gán các vai trò nội bộ của công ty: [MANAGER, DISPATCHER, WAREHOUSE_WORKER, DRIVER]"
                );
            }
            targetUser.setUserRoleType(request.role());
        }

        if (request.companyId() != null) {
            if (callerRole != UserRole.SYSTEM_ADMIN) {
                throw new AppException(
                        ErrorCode.UNAUTHORIZED,
                        "Chỉ tài khoản quản trị hệ thống mới được phép thay đổi công ty của người dùng"
                );
            }
            fu.se184491.loadmaster_be.entity.company.Company targetCompany = companyRepository.findById(request.companyId())
                    .orElseThrow(() -> new AppException(ErrorCode.COMPANY_NOT_FOUND));
            targetUser.setCompany(targetCompany);
        }

        targetUser.preUpdate();
        User saved = userRepository.save(targetUser);
        logChanges(ACTION_USER_UPDATED, currentUser, saved, before, accountSnapshot(saved));
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
        UserRole callerRole = currentUser.getUserRoleType();
        UserRole targetRole = targetUser.getUserRoleType();

        if (callerRole == UserRole.SYSTEM_ADMIN) {
            if (!isPlatformRole(targetRole) && targetRole != UserRole.ADMIN) {
                throw new AppException(
                        ErrorCode.UNAUTHORIZED,
                        "Tài khoản quản trị hệ thống không được phép khóa/mở khóa các vai trò nội bộ của công ty: [MANAGER, DISPATCHER, WAREHOUSE_WORKER, DRIVER]"
                );
            }
        } else if (callerRole == UserRole.ADMIN) {
            if (isPlatformRole(targetRole) || targetRole == UserRole.ADMIN) {
                throw new AppException(
                        ErrorCode.UNAUTHORIZED,
                        "Tài khoản ADMIN công ty chỉ được phép khóa/mở khóa các vai trò nội bộ trong cùng công ty: [MANAGER, DISPATCHER, WAREHOUSE_WORKER, DRIVER]"
                );
            }
        } else {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }

        UserStatus previousStatus = targetUser.getStatus();
        targetUser.setStatus(request.status());
        targetUser.preUpdate();

        User saved = userRepository.save(targetUser);
        if (previousStatus != saved.getStatus()) {
            logAccount(statusAction(saved.getStatus()), currentUser, saved,
                    statusValues(previousStatus), statusValues(saved.getStatus()));
        }
        return toUserListItemResponse(saved);
    }

    @Override
    @Transactional
    public String deleteUser(Long id) {
        User currentUser = currentUserService.getCurrentUser();
        if (currentUser.getId() != null && currentUser.getId().equals(id)) {
            throw new AppException(ErrorCode.INVALID_INPUT);
        }

        User targetUser = getScopedTargetUser(currentUser, id);
        UserRole callerRole = currentUser.getUserRoleType();
        UserRole targetRole = targetUser.getUserRoleType();

        if (callerRole == UserRole.SYSTEM_ADMIN) {
            if (!isPlatformRole(targetRole) && targetRole != UserRole.ADMIN) {
                throw new AppException(
                        ErrorCode.UNAUTHORIZED,
                        "Tài khoản quản trị hệ thống không được phép xóa các vai trò nội bộ của công ty: [MANAGER, DISPATCHER, WAREHOUSE_WORKER, DRIVER]"
                );
            }
        } else if (callerRole == UserRole.ADMIN) {
            if (isPlatformRole(targetRole) || targetRole == UserRole.ADMIN) {
                throw new AppException(
                        ErrorCode.UNAUTHORIZED,
                        "Tài khoản ADMIN công ty chỉ được phép xóa các vai trò nội bộ trong cùng công ty: [MANAGER, DISPATCHER, WAREHOUSE_WORKER, DRIVER]"
                );
            }
        } else {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }

        UserStatus previousStatus = targetUser.getStatus();
        targetUser.setStatus(UserStatus.INACTIVE);
        targetUser.preUpdate();
        userRepository.save(targetUser);
        logAccount(ACTION_USER_DELETED, currentUser, targetUser,
                statusValues(previousStatus), statusValues(UserStatus.INACTIVE));

        if (callerRole == UserRole.SYSTEM_ADMIN) {
            return "User deactivated successfully (will be permanently deleted after 3 months)";
        }
        return "User đã được xóa thành công";
    }

    @Override
    @Transactional
    @org.springframework.scheduling.annotation.Scheduled(cron = "0 0 * * * *")
    public void purgeInactiveUsersAfterThreeMonths() {
        java.time.LocalDateTime cutoff = java.time.LocalDateTime.now()
                .minusMonths(3)
                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS);

        java.util.List<User> expiredUsers =
                userRepository.findByStatusAndUpdatedAtBefore(UserStatus.INACTIVE, cutoff);

        for (User expiredUser : expiredUsers) {
            if (expiredUser.getKeycloakId() != null && !expiredUser.getKeycloakId().isBlank()) {
                try {
                    keycloakAdminClient.deleteUser(expiredUser.getKeycloakId());
                } catch (Exception ex) {
                    log.warn("Keycloak user {} already absent during 3-month inactive user purge", expiredUser.getKeycloakId());
                }
            }
            userRepository.delete(expiredUser);
        }
    }

    @Override
    @Transactional
    public ResetPasswordResponse resetPassword(Long id) {
        User currentUser = currentUserService.getCurrentUser();
        User targetUser = getScopedTargetUser(currentUser, id);

        String temporaryPassword = temporaryPasswordGenerator.generate();
        keycloakAdminClient.setPassword(targetUser.getKeycloakId(), temporaryPassword, true);
        // Không ghi mật khẩu vào nhật ký.
        logAccount(ACTION_USER_PASSWORD_RESET, currentUser, targetUser, null, null);

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

    static final String ENTITY_USER = "User";
    static final String ACTION_USER_CREATED = "USER_CREATED";
    static final String ACTION_USER_UPDATED = "USER_UPDATED";
    static final String ACTION_USER_LOCKED = "USER_LOCKED";
    static final String ACTION_USER_UNLOCKED = "USER_UNLOCKED";
    static final String ACTION_USER_STATUS_CHANGED = "USER_STATUS_CHANGED";
    static final String ACTION_USER_PASSWORD_RESET = "USER_PASSWORD_RESET";
    static final String ACTION_USER_DELETED = "USER_DELETED";

    private static String statusAction(UserStatus status) {
        if (status == UserStatus.LOCKED) {
            return ACTION_USER_LOCKED;
        }
        if (status == UserStatus.ACTIVE) {
            return ACTION_USER_UNLOCKED;
        }
        return ACTION_USER_STATUS_CHANGED;
    }

    private static Map<String, Object> statusValues(UserStatus status) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", status == null ? null : status.name());
        return values;
    }

    /** Các trường của tài khoản ghi vào nhật ký. Không bao giờ có mật khẩu. */
    private static Map<String, Object> accountSnapshot(User user) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("email", user.getEmail());
        values.put("fullName", user.getFullName());
        values.put("phoneNumber", user.getPhoneNumber());
        values.put("role", user.getUserRoleType() == null ? null : user.getUserRoleType().name());
        values.put("status", user.getStatus() == null ? null : user.getStatus().name());
        values.put("companyId", user.getCompany() == null ? null : user.getCompany().getId());
        return values;
    }

    /** Ghi riêng các trường đã đổi; không đổi gì thì không ghi. */
    private void logChanges(String action, User actor, User target,
                            Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> oldValues = new LinkedHashMap<>();
        Map<String, Object> newValues = new LinkedHashMap<>();
        after.forEach((field, value) -> {
            if (!Objects.equals(before.get(field), value)) {
                oldValues.put(field, before.get(field));
                newValues.put(field, value);
            }
        });
        if (!newValues.isEmpty()) {
            logAccount(action, actor, target, oldValues, newValues);
        }
    }

    /** Sự kiện về một tài khoản thuộc công ty của tài khoản đó, dù ai làm. */
    private void logAccount(String action, User actor, User target,
                            Map<String, Object> oldValues, Map<String, Object> newValues) {
        auditLogRepository.save(AuditLog.builder()
                .companyId(AuditCompany.ofAccount(target))
                .actionType(action)
                .entityName(ENTITY_USER)
                .entityId(target.getId() == null ? null : target.getId().toString())
                .user(actor)
                .oldValues(oldValues)
                .newValues(newValues)
                .createdAt(Instant.now())
                .build());
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
                user.getUserType() != null ? user.getUserType() : fu.se184491.loadmaster_be.constant.account.UserType.OFFICIAL,
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
