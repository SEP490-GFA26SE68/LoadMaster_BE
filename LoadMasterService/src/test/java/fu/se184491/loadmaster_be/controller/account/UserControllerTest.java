package fu.se184491.loadmaster_be.controller.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import fu.se184491.loadmaster_be.config.security.WebSecurityConfig;
import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.account.UserStatus;
import fu.se184491.loadmaster_be.dto.request.account.CreateUserRequest;
import fu.se184491.loadmaster_be.dto.response.account.CreateUserResponse;
import fu.se184491.loadmaster_be.dto.response.account.UserListItemResponse;
import fu.se184491.loadmaster_be.service.account.UserService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
@Import(WebSecurityConfig.class)
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
}
