package fu.se184491.loadmaster_be.controller.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import fu.se184491.loadmaster_be.config.security.WebSecurityConfig;
import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.account.UserStatus;
import fu.se184491.loadmaster_be.dto.PageResponse;
import fu.se184491.loadmaster_be.dto.request.account.CreateUserRequest;
import fu.se184491.loadmaster_be.dto.request.account.UpdateUserRequest;
import fu.se184491.loadmaster_be.dto.request.account.UpdateUserStatusRequest;
import fu.se184491.loadmaster_be.dto.response.account.ResetPasswordResponse;
import fu.se184491.loadmaster_be.dto.response.account.CreateUserResponse;
import fu.se184491.loadmaster_be.dto.response.account.UserListItemResponse;
import fu.se184491.loadmaster_be.exception.GlobalExceptionHandler;
import fu.se184491.loadmaster_be.service.account.UserService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.domain.PageImpl;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
@Import({WebSecurityConfig.class, GlobalExceptionHandler.class})
@AutoConfigureMockMvc(addFilters = false)
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private JwtAuthenticationConverter jwtAuthenticationConverter;

    private ObjectMapper objectMapper;
    private CreateUserRequest createRequest;
    private CreateUserResponse createResponse;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        createRequest = new CreateUserRequest(
                "admin@acme.vn",
                "Acme Admin",
                "0901234567",
                10L,
                UserRole.ADMIN
        );
        UserListItemResponse userItem = new UserListItemResponse(
                100L,
                "kc-100",
                "admin@acme.vn",
                "Acme Admin",
                "0901234567",
                UserRole.ADMIN,
                UserStatus.ACTIVE,
                10L,
                "Acme Logistics"
        );
        createResponse = new CreateUserResponse(userItem, "Tmp#123456");
    }

    @Test
    @WithMockUser(authorities = "USERS_CREATE")
    void createUser_serializesCreatedAtAsUtcWithoutChangingSubscriptionFormat() throws Exception {
        UserListItemResponse item = new UserListItemResponse(
                100L, "kc-100", "admin@acme.vn", "Acme Admin", "0901234567",
                UserRole.ADMIN, null, UserStatus.ACTIVE, 10L, "Acme Logistics",
                null, null, null, null, null, null,
                LocalDateTime.of(2026, 12, 4, 8, 30), null,
                Instant.parse("2026-11-04T15:00:00Z"));
        when(userService.createUser(any(CreateUserRequest.class)))
                .thenReturn(new CreateUserResponse(item, "Tmp#123456"));

        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.user.createdAt").value("2026-11-04T15:00:00Z"))
                .andExpect(jsonPath("$.data.user.subscriptionExpiresAt").value("2026-12-04 08:30:00"));
    }

    private UserListItemResponse timestampedUser() {
        return new UserListItemResponse(
                100L, "kc-100", "admin@acme.vn", "Acme Admin", "0901234567",
                UserRole.ADMIN, null, UserStatus.ACTIVE, 10L, "Acme Logistics",
                null, null, null, null, null, null,
                LocalDateTime.of(2026, 12, 4, 8, 30), null,
                OffsetDateTime.parse("2026-11-04T22:00:00+07:00").toInstant());
    }

    @Test
    @WithMockUser(authorities = "USERS_READ")
    void listUsers_serializesCreatedAtAsUtcInPageData() throws Exception {
        when(userService.getUsers(eq(0), eq(20), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(PageResponse.of(new PageImpl<>(List.of(timestampedUser()))));
        mockMvc.perform(get("/api/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].createdAt").value("2026-11-04T15:00:00Z"))
                .andExpect(jsonPath("$.data[0].subscriptionExpiresAt").value("2026-12-04 08:30:00"));
    }

    @Test
    @WithMockUser(authorities = "USERS_UPDATE")
    void updateUser_serializesCreatedAtAsUtc() throws Exception {
        when(userService.updateUser(eq(100L), any(UpdateUserRequest.class))).thenReturn(timestampedUser());
        mockMvc.perform(put("/api/users/100").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Acme Admin\",\"phoneNumber\":\"0901234567\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.createdAt").value("2026-11-04T15:00:00Z"));
    }

    @Test
    @WithMockUser(authorities = "USERS_LOCK")
    void updateStatus_serializesCreatedAtAsUtc() throws Exception {
        when(userService.updateUserStatus(eq(100L), any(UpdateUserStatusRequest.class))).thenReturn(timestampedUser());
        mockMvc.perform(patch("/api/users/100/status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"LOCKED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.createdAt").value("2026-11-04T15:00:00Z"));
    }

    @Test
    @WithMockUser(authorities = "USERS_UPDATE")
    void nestedUserInResetResponse_serializesCreatedAtAsUtcWithoutInvokingPasswordLogic() throws Exception {
        // Serialization only: the service is mocked, so no password or Keycloak operation runs.
        when(userService.resetPassword(100L)).thenReturn(new ResetPasswordResponse(100L, timestampedUser(), "Tmp#123456"));
        mockMvc.perform(post("/api/users/100/reset-password"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.createdAt").value("2026-11-04T15:00:00Z"));
    }

    @Test
    @DisplayName("POST /api/users - Allows caller with USERS_CREATE authority")
    @WithMockUser(authorities = "USERS_CREATE")
    void createUser_withUsersCreateAuthority_returnsCreated() throws Exception {
        when(userService.createUser(any(CreateUserRequest.class))).thenReturn(createResponse);

        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.temporaryPassword").value("Tmp#123456"))
                .andExpect(jsonPath("$.data.user.email").value("admin@acme.vn"));

        verify(userService).createUser(any(CreateUserRequest.class));
    }

    @Test
    @DisplayName("POST /api/users - Denies caller without USERS_CREATE authority with 403 Forbidden")
    @WithMockUser(roles = "DRIVER")
    void createUser_withoutUsersCreateAuthority_returnsForbidden() throws Exception {
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isForbidden());

        verify(userService, never()).createUser(any());
    }

    @Test
    @DisplayName("PUT /api/users/{id} - Allows caller with USERS_UPDATE authority")
    @WithMockUser(authorities = "USERS_UPDATE")
    void updateUser_withUsersUpdateAuthority_returnsOk() throws Exception {
        String body = """
                {
                  "fullName": "Updated Name",
                  "phoneNumber": "0912345678",
                  "role": "DISPATCHER"
                }
                """;

        mockMvc.perform(put("/api/users/100")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("PUT /api/users/{id} & PATCH /api/users/{id}/status - Denies ROLE_SYSTEM_MANAGER with 403 Forbidden")
    @WithMockUser(roles = "SYSTEM_MANAGER")
    void updateUser_and_updateUserStatus_whenSystemManager_returnsForbidden() throws Exception {
        String updateBody = """
                {
                  "fullName": "Updated Name",
                  "phoneNumber": "0912345678",
                  "role": "ADMIN"
                }
                """;
        mockMvc.perform(put("/api/users/100")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isForbidden());

        String statusBody = """
                {
                  "status": "LOCKED"
                }
                """;
        mockMvc.perform(patch("/api/users/100/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(statusBody))
                .andExpect(status().isForbidden());

        verify(userService, never()).updateUser(any(), any());
        verify(userService, never()).updateUserStatus(any(), any());
    }

    @Test
    @DisplayName("PATCH /api/users/{id}/status - Allows caller with USERS_LOCK authority")
    @WithMockUser(authorities = "USERS_LOCK")
    void updateUserStatus_withUsersLockAuthority_returnsOk() throws Exception {
        String body = """
                {
                  "status": "LOCKED"
                }
                """;

        mockMvc.perform(patch("/api/users/100/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("DELETE /api/users/{id} - Allows caller with USERS_DELETE authority")
    @WithMockUser(authorities = "USERS_DELETE")
    void deleteUser_withUsersDeleteAuthority_returnsOk() throws Exception {
        mockMvc.perform(delete("/api/users/100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("POST /api/users/{id}/reset-password - Allows caller with USERS_UPDATE authority")
    @WithMockUser(authorities = "USERS_UPDATE")
    void resetPassword_withUsersUpdateAuthority_returnsOk() throws Exception {
        mockMvc.perform(post("/api/users/100/reset-password"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("PATCH /api/users/{id}/status - Invalid enum value returns clean Vietnamese message without raw Jackson class names")
    @WithMockUser(authorities = "USERS_LOCK")
    void updateUserStatus_withInvalidEnum_returnsCleanBadRequestMessage() throws Exception {
        String body = """
                {
                  "status": "hihi"
                }
                """;

        mockMvc.perform(patch("/api/users/100/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message", containsString("status")))
                .andExpect(jsonPath("$.message", containsString("hihi")))
                .andExpect(jsonPath("$.message", not(containsString("fu.se184491"))))
                .andExpect(jsonPath("$.message", not(containsString("StreamReadFeature"))));
    }

    @Test
    @DisplayName("PUT /api/users/{id} - Phone number exceeding 20 chars returns 400 Bad Request instead of 500")
    @WithMockUser(authorities = "USERS_UPDATE")
    void updateUser_withTooLongPhoneNumber_returnsBadRequest() throws Exception {
        String body = """
                {
                  "fullName": "Updated Name",
                  "phoneNumber": "09012345678901234567890123456789",
                  "role": "DISPATCHER"
                }
                """;

        mockMvc.perform(put("/api/users/100")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        verify(userService, never()).updateUser(any(), any());
    }

    @Test
    @DisplayName("POST /api/users - Company ADMIN passing invalid role enum only sees internal company roles in error message")
    @WithMockUser( authorities = {"ROLE_ADMIN", "USERS_CREATE"})
    void createUser_whenCompanyAdminPassesInvalidRoleEnum_returnsOnlyInternalRoles() throws Exception {
        String body = """
                {
                  "email": "staff@acme.vn",
                  "fullName": "Staff Name",
                  "phoneNumber": "0901234567",
                  "companyId": 1,
                  "role": "hihi"
                }
                """;

        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message", containsString("[MANAGER, DISPATCHER, WAREHOUSE_WORKER, DRIVER]")))
                .andExpect(jsonPath("$.message", not(containsString("SYSTEM_ADMIN"))))
                .andExpect(jsonPath("$.message", not(containsString("SYSTEM_MANAGER"))))
                .andExpect(jsonPath("$.message", not(containsString("SYSTEM_SUPPORTER"))));
    }

    @Test
    @DisplayName("POST /api/users - SYSTEM_MANAGER passing invalid role enum only sees company-scoped roles in error message")
    @WithMockUser(authorities = {"ROLE_SYSTEM_MANAGER", "USERS_CREATE"})
    void createUser_whenSystemManagerPassesInvalidRoleEnum_returnsCompanyScopedRoles() throws Exception {
        String body = """
                {
                  "email": "admin@acme.vn",
                  "fullName": "Admin Name",
                  "phoneNumber": "0901234567",
                  "companyId": 1,
                  "role": "hihi"
                }
                """;

        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message", containsString("[ADMIN, MANAGER, DISPATCHER, WAREHOUSE_WORKER, DRIVER]")))
                .andExpect(jsonPath("$.message", not(containsString("SYSTEM_ADMIN"))));
    }
}
