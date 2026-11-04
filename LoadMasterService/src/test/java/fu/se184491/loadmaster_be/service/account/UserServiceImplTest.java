package fu.se184491.loadmaster_be.service.account;

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
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.company.CompanyAgreement;
import fu.se184491.loadmaster_be.entity.support.SupportTicket;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.helpers.TemporaryPasswordGenerator;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import fu.se184491.loadmaster_be.repository.company.CompanyAgreementRepository;
import fu.se184491.loadmaster_be.repository.company.CompanyRepository;
import fu.se184491.loadmaster_be.service.account.Impl.UserServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private CompanyRepository companyRepository;

    @Mock
    private KeycloakAdminClient keycloakAdminClient;

    @Mock
    private CurrentUserService currentUserService;

    @Mock
    private TemporaryPasswordGenerator temporaryPasswordGenerator;

    @Mock
    private CompanyAgreementRepository companyAgreementRepository;

    @Mock
    private BrevoClient brevoClient;

    @InjectMocks
    private UserServiceImpl userService;

    private Company company10;
    private Company company99;
    private User systemAdminCaller;
    private User systemManagerCaller;
    private User companyAdminCaller;
    private User targetEmployeeInCompany10;
    private User targetEmployeeInCompany99;

    @BeforeEach
    void setUp() {
        company10 = Company.builder()
                .id(10L)
                .companyCode("COMP-ACME")
                .companyName("Acme Logistics")
                .taxCode("0101234567")
                .billingEmail("admin@acme.vn")
                .build();

        company99 = Company.builder()
                .id(99L)
                .companyName("Other Corp")
                .build();

        systemAdminCaller = User.builder()
                .id(999L)
                .email("sysadmin@loadmaster.vn")
                .userRoleType(UserRole.SYSTEM_ADMIN)
                .status(UserStatus.ACTIVE)
                .build();

        systemManagerCaller = User.builder()
                .id(1L)
                .email("sysmanager@loadmaster.vn")
                .userRoleType(UserRole.SYSTEM_MANAGER)
                .status(UserStatus.ACTIVE)
                .build();

        companyAdminCaller = User.builder()
                .id(2L)
                .email("admin@acme.vn")
                .company(company10)
                .userRoleType(UserRole.ADMIN)
                .status(UserStatus.ACTIVE)
                .build();

        targetEmployeeInCompany10 = User.builder()
                .id(100L)
                .keycloakId("kc-100")
                .email("driver@acme.vn")
                .fullName("Old Driver")
                .phoneNumber("0901111111")
                .company(company10)
                .userRoleType(UserRole.DRIVER)
                .status(UserStatus.ACTIVE)
                .build();

        targetEmployeeInCompany99 = User.builder()
                .id(200L)
                .keycloakId("kc-200")
                .email("driver@other.vn")
                .fullName("Other Driver")
                .phoneNumber("0902222222")
                .company(company99)
                .userRoleType(UserRole.DRIVER)
                .status(UserStatus.ACTIVE)
                .build();
    }

    @Test
    @DisplayName("Seam 1 - createUser & updateUser: Initializes non-null createdAt and updatedAt equal to each other in yyyy-MM-dd HH:mm:ss format, and updates only updatedAt >= createdAt on update")
    void createAndUpdateUser_setsCreatedAtAndUpdatedAtInSecondsFormat() {
        when(currentUserService.getCurrentUser()).thenReturn(companyAdminCaller);
        when(userRepository.existsByEmail("dispatcher@acme.vn")).thenReturn(false);
        when(companyRepository.findById(10L)).thenReturn(Optional.of(company10));
        when(temporaryPasswordGenerator.generate()).thenReturn("Tmp#654321");
        when(keycloakAdminClient.createUser("dispatcher@acme.vn", "Acme Dispatcher")).thenReturn("kc-disp");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            if (u.getId() == null) {
                u.setId(51L);
            }
            return u;
        });

        CreateUserRequest createReq = new CreateUserRequest(
                "dispatcher@acme.vn",
                "Acme Dispatcher",
                "0908888888",
                10L,
                UserRole.DISPATCHER
        );

        CreateUserResponse createRes = userService.createUser(createReq);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User createdEntity = captor.getValue();

        assertNotNull(createRes.user().createdAt(), "createdAt in response must not be null");
        assertEquals(0, createRes.user().createdAt().getNano(), "createdAt must be truncated to seconds");
        assertNotNull(createdEntity.getCreatedAt(), "Entity createdAt must not be null");
        assertNotNull(createdEntity.getUpdatedAt(), "Entity updatedAt must not be null on initial creation");
        assertEquals(createdEntity.getCreatedAt(), createdEntity.getUpdatedAt(), "Initial updatedAt must equal createdAt");

        SupportTicket.LocalDateTimeSecondsConverter converter = new SupportTicket.LocalDateTimeSecondsConverter();
        String dbCreated = converter.convertToDatabaseColumn(createdEntity.getCreatedAt());
        String dbUpdated = converter.convertToDatabaseColumn(createdEntity.getUpdatedAt());
        assertTrue(dbCreated.matches("^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01]) ([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]$"));
        assertEquals(dbCreated, dbUpdated);

        LocalDateTime originalCreatedAt = LocalDateTime.of(2026, 11, 1, 8, 30, 0);
        createdEntity.setCreatedAt(originalCreatedAt);
        createdEntity.setUpdatedAt(originalCreatedAt);
        when(userRepository.findById(51L)).thenReturn(Optional.of(createdEntity));

        userService.updateUser(51L, new UpdateUserRequest("Updated Dispatcher", "0909999999", UserRole.MANAGER));

        assertEquals(originalCreatedAt, createdEntity.getCreatedAt(), "createdAt must not change on update");
        assertNotNull(createdEntity.getUpdatedAt());
        assertEquals(0, createdEntity.getUpdatedAt().getNano(), "updatedAt must be truncated to seconds");
        assertFalse(createdEntity.getUpdatedAt().isBefore(createdEntity.getCreatedAt()), "updatedAt must be >= createdAt");
    }

    @Test
    @DisplayName("Seam 2 - createUser: SYSTEM_MANAGER is NOT allowed to create user accounts (only SYSTEM_ADMIN can create Company ADMIN)")
    void createUser_whenCallerIsSystemManager_throwsUnauthorized() {
        when(currentUserService.getCurrentUser()).thenReturn(systemManagerCaller);

        CreateUserRequest request = new CreateUserRequest(
                "newadmin@acme.vn",
                "New Admin",
                "0909999999",
                10L,
                UserRole.ADMIN
        );

        AppException ex = assertThrows(AppException.class, () -> userService.createUser(request));
        assertEquals(ErrorCode.UNAUTHORIZED, ex.getErrorCode());
        verify(keycloakAdminClient, never()).createUser(any(), any());
    }

    @Test
    @DisplayName("Seam 2 - createUser: SYSTEM_ADMIN is forbidden from creating internal company roles (MANAGER, DISPATCHER, WAREHOUSE_WORKER, DRIVER)")
    void createUser_whenCallerIsSystemAdminAndCreatesInternalCompanyRole_throwsUnauthorized() {
        when(currentUserService.getCurrentUser()).thenReturn(systemAdminCaller);

        for (UserRole internalRole : List.of(UserRole.MANAGER, UserRole.DISPATCHER, UserRole.WAREHOUSE_WORKER, UserRole.DRIVER)) {
            CreateUserRequest request = new CreateUserRequest(
                    "staff@acme.vn",
                    "Staff Member",
                    "0909999999",
                    10L,
                    internalRole
            );
            AppException ex = assertThrows(AppException.class, () -> userService.createUser(request));
            assertEquals(ErrorCode.UNAUTHORIZED, ex.getErrorCode());
        }
        verify(keycloakAdminClient, never()).createUser(any(), any());
    }

    @Test
    @DisplayName("Seam 2 - createUser: SYSTEM_ADMIN creates Company ADMIN after agreement is SIGNED using signerEmail from CompanyAgreement and sends credentials email via Brevo")
    void createUser_whenCallerIsSystemAdminAndAgreementIsSigned_createsAdminAndSendsEmail() {
        when(currentUserService.getCurrentUser()).thenReturn(systemAdminCaller);
        when(companyRepository.findById(10L)).thenReturn(Optional.of(company10));

        CompanyAgreement signedAgreement = CompanyAgreement.builder()
                .id(77L)
                .company(company10)
                .status(AgreementStatus.SIGNED)
                .signerEmail("signed-admin@acme.vn")
                .signerName("Signed Acme Representative")
                .signerPhone("0901234567")
                .build();
        when(companyAgreementRepository.findFirstByCompanyIdOrderByCreatedAtDesc(10L))
                .thenReturn(Optional.of(signedAgreement));
        when(userRepository.existsByEmail("signed-admin@acme.vn")).thenReturn(false);
        when(temporaryPasswordGenerator.generate()).thenReturn("Tmp#123456");
        when(keycloakAdminClient.createUser("signed-admin@acme.vn", "Signed Acme Representative")).thenReturn("kc-new-admin");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(50L);
            return u;
        });

        CreateUserRequest request = new CreateUserRequest(
                "signed-admin@acme.vn",
                "Signed Acme Representative",
                "0901234567",
                10L,
                UserRole.ADMIN
        );

        CreateUserResponse response = userService.createUser(request);

        assertNotNull(response);
        assertEquals("Tmp#123456", response.temporaryPassword());
        assertEquals(UserRole.ADMIN, response.user().userRoleType());
        assertEquals("signed-admin@acme.vn", response.user().email());
        assertEquals(10L, response.user().companyId());

        verify(brevoClient).sendEmail(
                eq("signed-admin@acme.vn"),
                contains("LoadMaster"),
                contains("Tmp#123456")
        );
    }

    @Test
    @DisplayName("Seam 2 - createUser: SYSTEM_ADMIN cannot create Company ADMIN if CompanyAgreement is still PENDING (unsigned)")
    void createUser_whenCallerIsSystemAdminAndAgreementIsPending_throwsInvalidInput() {
        when(currentUserService.getCurrentUser()).thenReturn(systemAdminCaller);
        when(companyRepository.findById(10L)).thenReturn(Optional.of(company10));

        CompanyAgreement pendingAgreement = CompanyAgreement.builder()
                .id(78L)
                .company(company10)
                .status(AgreementStatus.PENDING)
                .signerEmail("pending@acme.vn")
                .build();
        when(companyAgreementRepository.findFirstByCompanyIdOrderByCreatedAtDesc(10L))
                .thenReturn(Optional.of(pendingAgreement));

        CreateUserRequest request = new CreateUserRequest(
                "pending@acme.vn",
                "Pending Admin",
                "0901234567",
                10L,
                UserRole.ADMIN
        );

        AppException ex = assertThrows(AppException.class, () -> userService.createUser(request));
        assertEquals(ErrorCode.INVALID_INPUT, ex.getErrorCode());
        verify(keycloakAdminClient, never()).createUser(any(), any());
        verify(brevoClient, never()).sendEmail(any(), any(), any());
    }

    @Test
    @DisplayName("createUser - Company ADMIN can create internal role (DISPATCHER) for own company")
    void createUser_whenCallerIsCompanyAdmin_allowsCreatingInternalRoleForOwnCompany() {
        when(currentUserService.getCurrentUser()).thenReturn(companyAdminCaller);
        when(userRepository.existsByEmail("dispatcher@acme.vn")).thenReturn(false);
        when(companyRepository.findById(10L)).thenReturn(Optional.of(company10));
        when(temporaryPasswordGenerator.generate()).thenReturn("Tmp#654321");
        when(keycloakAdminClient.createUser("dispatcher@acme.vn", "Acme Dispatcher")).thenReturn("kc-disp");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(51L);
            return u;
        });

        CreateUserRequest request = new CreateUserRequest(
                "dispatcher@acme.vn",
                "Acme Dispatcher",
                "0908888888",
                10L,
                UserRole.DISPATCHER
        );

        CreateUserResponse response = userService.createUser(request);

        assertNotNull(response);
        assertEquals(UserRole.DISPATCHER, response.user().userRoleType());
        assertEquals(10L, response.user().companyId());
    }

    @Test
    @DisplayName("createUser - Company ADMIN cannot create user for another company (throws UNAUTHORIZED)")
    void createUser_whenCallerIsCompanyAdminAndTargetsAnotherCompany_throwsUnauthorized() {
        when(currentUserService.getCurrentUser()).thenReturn(companyAdminCaller);

        CreateUserRequest request = new CreateUserRequest(
                "dispatcher@other.vn",
                "Other Dispatcher",
                "0908888888",
                99L,
                UserRole.DISPATCHER
        );

        AppException ex = assertThrows(AppException.class, () -> userService.createUser(request));
        assertEquals(ErrorCode.UNAUTHORIZED, ex.getErrorCode());
        verify(keycloakAdminClient, never()).createUser(any(), any());
    }

    @Test
    @DisplayName("createUser - Company ADMIN cannot create ADMIN or platform role (throws UNAUTHORIZED)")
    void createUser_whenCallerIsCompanyAdminAndCreatesAdminRole_throwsUnauthorized() {
        when(currentUserService.getCurrentUser()).thenReturn(companyAdminCaller);

        CreateUserRequest request = new CreateUserRequest(
                "anotheradmin@acme.vn",
                "Another Admin",
                "0908888888",
                10L,
                UserRole.ADMIN
        );

        AppException ex = assertThrows(AppException.class, () -> userService.createUser(request));
        assertEquals(ErrorCode.UNAUTHORIZED, ex.getErrorCode());
        verify(keycloakAdminClient, never()).createUser(any(), any());
    }

    @Test
    @DisplayName("getUsers - SYSTEM_MANAGER can list users across companies")
    @SuppressWarnings("unchecked")
    void getUsers_whenCallerIsSystemManager_returnsPagedUsers() {
        when(currentUserService.getCurrentUser()).thenReturn(systemManagerCaller);
        when(userRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(companyAdminCaller)));

        PageResponse<UserListItemResponse> result = userService.getUsers(0, 20, null, null, null, 10L);

        assertNotNull(result);
        assertEquals(1, result.getData().size());
    }

    @Test
    @DisplayName("updateUser - Company ADMIN can update user in own company")
    void updateUser_whenCallerIsCompanyAdminForOwnCompany_updatesUserFields() {
        when(currentUserService.getCurrentUser()).thenReturn(companyAdminCaller);
        when(userRepository.findById(100L)).thenReturn(Optional.of(targetEmployeeInCompany10));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateUserRequest request = new UpdateUserRequest(
                "Updated Driver",
                "0912345678",
                UserRole.DISPATCHER
        );

        UserListItemResponse response = userService.updateUser(100L, request);

        assertEquals("Updated Driver", response.fullName());
        assertEquals("0912345678", response.phoneNumber());
        assertEquals(UserRole.DISPATCHER, response.userRoleType());
    }

    @Test
    @DisplayName("updateUser - Company ADMIN cannot update user in another company")
    void updateUser_whenCallerIsCompanyAdminForOtherCompany_throwsUnauthorized() {
        when(currentUserService.getCurrentUser()).thenReturn(companyAdminCaller);
        when(userRepository.findById(200L)).thenReturn(Optional.of(targetEmployeeInCompany99));

        UpdateUserRequest request = new UpdateUserRequest(
                "Hacked Driver",
                "0912345678",
                UserRole.DISPATCHER
        );

        AppException ex = assertThrows(AppException.class, () -> userService.updateUser(200L, request));
        assertEquals(ErrorCode.UNAUTHORIZED, ex.getErrorCode());
    }

    @Test
    @DisplayName("updateUser - SYSTEM_ADMIN can update Company ADMIN, but is forbidden from updating internal company staff or assigning internal roles")
    void updateUser_whenSystemAdmin_allowsCompanyAdminAndRejectsInternalStaff() {
        when(currentUserService.getCurrentUser()).thenReturn(systemAdminCaller);

        // 1. Forbidden from updating internal company staff (DRIVER)
        when(userRepository.findById(100L)).thenReturn(Optional.of(targetEmployeeInCompany10));
        UpdateUserRequest staffReq = new UpdateUserRequest("New Name", "0912345678", UserRole.DRIVER);
        AppException staffEx = assertThrows(AppException.class, () -> userService.updateUser(100L, staffReq));
        assertEquals(ErrorCode.UNAUTHORIZED, staffEx.getErrorCode());

        // 2. Forbidden from demoting Company ADMIN to internal staff role (DISPATCHER)
        when(userRepository.findById(2L)).thenReturn(Optional.of(companyAdminCaller));
        UpdateUserRequest demoteReq = new UpdateUserRequest("Admin Name", "0912345678", UserRole.DISPATCHER);
        AppException demoteEx = assertThrows(AppException.class, () -> userService.updateUser(2L, demoteReq));
        assertEquals(ErrorCode.UNAUTHORIZED, demoteEx.getErrorCode());

        // 3. Allowed to update Company ADMIN keeping role ADMIN
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        UpdateUserRequest validAdminReq = new UpdateUserRequest("Updated Company Admin", "0912345678", UserRole.ADMIN);
        UserListItemResponse res = userService.updateUser(2L, validAdminReq);
        assertEquals("Updated Company Admin", res.fullName());
        assertEquals(UserRole.ADMIN, res.userRoleType());
    }

    @Test
    @DisplayName("updateUserStatus - Toggles user status, prevents locking oneself, and enforces SYSTEM_ADMIN vs Company ADMIN role boundaries")
    void updateUserStatus_preventsSelfLockAndUpdatesTargetStatus() {
        when(currentUserService.getCurrentUser()).thenReturn(companyAdminCaller);

        AppException selfEx = assertThrows(
                AppException.class,
                () -> userService.updateUserStatus(2L, new UpdateUserStatusRequest(UserStatus.LOCKED))
        );
        assertEquals(ErrorCode.INVALID_INPUT, selfEx.getErrorCode());

        when(userRepository.findById(100L)).thenReturn(Optional.of(targetEmployeeInCompany10));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        UserListItemResponse result = userService.updateUserStatus(
                100L,
                new UpdateUserStatusRequest(UserStatus.LOCKED)
        );
        assertEquals(UserStatus.LOCKED, result.status());

        // SYSTEM_ADMIN cannot lock internal company staff (DRIVER)
        when(currentUserService.getCurrentUser()).thenReturn(systemAdminCaller);
        AppException sysAdminStaffLockEx = assertThrows(
                AppException.class,
                () -> userService.updateUserStatus(100L, new UpdateUserStatusRequest(UserStatus.LOCKED))
        );
        assertEquals(ErrorCode.UNAUTHORIZED, sysAdminStaffLockEx.getErrorCode());
    }

    @Test
    @DisplayName("deleteUser - Company ADMIN soft-deletes internal staff in same company (status=INACTIVE, keeps Keycloak & DB record) and prevents self-deletion")
    void deleteUser_whenCompanyAdminDeletesSameCompanyStaff_softDeletesAndPreventsSelfDelete() {
        when(currentUserService.getCurrentUser()).thenReturn(companyAdminCaller);

        AppException selfEx = assertThrows(AppException.class, () -> userService.deleteUser(2L));
        assertEquals(ErrorCode.INVALID_INPUT, selfEx.getErrorCode());

        targetEmployeeInCompany10.prePersist();
        when(userRepository.findById(100L)).thenReturn(Optional.of(targetEmployeeInCompany10));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        String msg = userService.deleteUser(100L);

        assertEquals("User đã được xóa thành công", msg);
        assertEquals(UserStatus.INACTIVE, targetEmployeeInCompany10.getStatus());
        assertNotNull(targetEmployeeInCompany10.getUpdatedAt());
        verify(userRepository).save(targetEmployeeInCompany10);
        verify(userRepository, never()).delete(any(User.class));
        verify(keycloakAdminClient, never()).deleteUser(any());
    }

    @Test
    @DisplayName("deleteUser - Company ADMIN cannot delete staff from another company or another Company ADMIN")
    void deleteUser_whenCompanyAdminDeletesOtherCompanyOrAnotherAdmin_throwsUnauthorized() {
        when(currentUserService.getCurrentUser()).thenReturn(companyAdminCaller);
        when(userRepository.findById(200L)).thenReturn(Optional.of(targetEmployeeInCompany99));

        AppException otherCompanyEx = assertThrows(AppException.class, () -> userService.deleteUser(200L));
        assertEquals(ErrorCode.UNAUTHORIZED, otherCompanyEx.getErrorCode());

        User anotherAdminSameCompany = User.builder()
                .id(105L)
                .keycloakId("kc-105")
                .email("admin2@acme.vn")
                .company(company10)
                .userRoleType(UserRole.ADMIN)
                .status(UserStatus.ACTIVE)
                .build();
        when(userRepository.findById(105L)).thenReturn(Optional.of(anotherAdminSameCompany));

        AppException anotherAdminEx = assertThrows(AppException.class, () -> userService.deleteUser(105L));
        assertEquals(ErrorCode.UNAUTHORIZED, anotherAdminEx.getErrorCode());
    }

    @Test
    @DisplayName("deleteUser - SYSTEM_ADMIN soft-deletes Company ADMIN (status=INACTIVE), but is forbidden from deleting internal company staff")
    void deleteUser_whenSystemAdminDeletes_allowsCompanyAdminSoftDeleteAndRejectsInternalStaff() {
        when(currentUserService.getCurrentUser()).thenReturn(systemAdminCaller);

        // 1. SYSTEM_ADMIN forbidden from deleting internal company staff (e.g. DRIVER)
        when(userRepository.findById(100L)).thenReturn(Optional.of(targetEmployeeInCompany10));
        AppException internalStaffEx = assertThrows(AppException.class, () -> userService.deleteUser(100L));
        assertEquals(ErrorCode.UNAUTHORIZED, internalStaffEx.getErrorCode());
        verify(userRepository, never()).save(targetEmployeeInCompany10);

        // 2. SYSTEM_ADMIN allowed to soft-delete Company ADMIN
        companyAdminCaller.prePersist();
        when(userRepository.findById(2L)).thenReturn(Optional.of(companyAdminCaller));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        String msg = userService.deleteUser(2L);

        assertEquals("User deactivated successfully (will be permanently deleted after 3 months)", msg);
        assertEquals(UserStatus.INACTIVE, companyAdminCaller.getStatus());
        verify(userRepository).save(companyAdminCaller);
        verify(userRepository, never()).delete(any(User.class));
        verify(keycloakAdminClient, never()).deleteUser(any());
    }

    @Test
    @DisplayName("purgeInactiveUsersAfterThreeMonths - Permanently deletes INACTIVE users older than 3 months from both Keycloak and DB")
    void purgeInactiveUsersAfterThreeMonths_permanentlyDeletesFromKeycloakAndDb() {
        User expiredInactiveUser = User.builder()
                .id(301L)
                .keycloakId("kc-expired-301")
                .email("old-deleted@acme.vn")
                .company(company10)
                .userRoleType(UserRole.ADMIN)
                .status(UserStatus.INACTIVE)
                .createdAt(LocalDateTime.now().minusMonths(5))
                .updatedAt(LocalDateTime.now().minusMonths(3).minusDays(1))
                .build();

        when(userRepository.findByStatusAndUpdatedAtBefore(eq(UserStatus.INACTIVE), any(LocalDateTime.class)))
                .thenReturn(List.of(expiredInactiveUser));

        userService.purgeInactiveUsersAfterThreeMonths();

        verify(keycloakAdminClient).deleteUser("kc-expired-301");
        verify(userRepository).delete(expiredInactiveUser);
    }

    @Test
    @DisplayName("resetPassword - Generates temporary password and updates Keycloak")
    void resetPassword_generatesTempPasswordAndUpdatesKeycloak() {
        when(currentUserService.getCurrentUser()).thenReturn(companyAdminCaller);
        when(userRepository.findById(100L)).thenReturn(Optional.of(targetEmployeeInCompany10));
        when(temporaryPasswordGenerator.generate()).thenReturn("NewTmp#999");

        ResetPasswordResponse response = userService.resetPassword(100L);

        assertEquals(100L, response.userId());
        assertEquals("NewTmp#999", response.temporaryPassword());
        verify(keycloakAdminClient).setPassword("kc-100", "NewTmp#999");
    }
}
