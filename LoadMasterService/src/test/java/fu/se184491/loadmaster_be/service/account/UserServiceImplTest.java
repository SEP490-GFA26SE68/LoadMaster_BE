package fu.se184491.loadmaster_be.service.account;

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
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.helpers.TemporaryPasswordGenerator;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import fu.se184491.loadmaster_be.repository.company.CompanyRepository;
import fu.se184491.loadmaster_be.service.account.Impl.UserServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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

    @InjectMocks
    private UserServiceImpl userService;

    private Company company10;
    private Company company99;
    private User systemManagerCaller;
    private User companyAdminCaller;
    private User targetEmployeeInCompany10;
    private User targetEmployeeInCompany99;

    @BeforeEach
    void setUp() {
        company10 = Company.builder()
                .id(10L)
                .companyName("Acme Logistics")
                .build();

        company99 = Company.builder()
                .id(99L)
                .companyName("Other Corp")
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
    @DisplayName("createUser - SYSTEM_MANAGER can create Company ADMIN for any valid company")
    void createUser_whenCallerIsSystemManager_allowsCreatingCompanyAdmin() {
        when(currentUserService.getCurrentUser()).thenReturn(systemManagerCaller);
        when(userRepository.existsByEmail("newadmin@acme.vn")).thenReturn(false);
        when(companyRepository.findById(10L)).thenReturn(Optional.of(company10));
        when(temporaryPasswordGenerator.generate()).thenReturn("Tmp#123456");
        when(keycloakAdminClient.createUser("newadmin@acme.vn", "New Admin")).thenReturn("kc-new-admin");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(50L);
            return u;
        });

        CreateUserRequest request = new CreateUserRequest(
                "newadmin@acme.vn",
                "New Admin",
                "0909999999",
                10L,
                UserRole.ADMIN
        );

        CreateUserResponse response = userService.createUser(request);

        assertNotNull(response);
        assertEquals("Tmp#123456", response.temporaryPassword());
        assertEquals(UserRole.ADMIN, response.user().userRoleType());
        assertEquals(10L, response.user().companyId());
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
    @DisplayName("updateUserStatus - Toggles user status and prevents locking oneself")
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
    }

    @Test
    @DisplayName("deleteUser - Deletes user in Keycloak and DB, prevents self-deletion")
    void deleteUser_deletesInKeycloakAndDbAndPreventsSelfDelete() {
        when(currentUserService.getCurrentUser()).thenReturn(companyAdminCaller);

        AppException selfEx = assertThrows(AppException.class, () -> userService.deleteUser(2L));
        assertEquals(ErrorCode.INVALID_INPUT, selfEx.getErrorCode());

        when(userRepository.findById(100L)).thenReturn(Optional.of(targetEmployeeInCompany10));

        userService.deleteUser(100L);

        verify(keycloakAdminClient).deleteUser("kc-100");
        verify(userRepository).delete(targetEmployeeInCompany10);
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
