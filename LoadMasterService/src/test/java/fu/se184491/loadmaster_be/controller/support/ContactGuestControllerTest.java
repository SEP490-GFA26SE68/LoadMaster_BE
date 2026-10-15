package fu.se184491.loadmaster_be.controller.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import fu.se184491.loadmaster_be.config.security.RestAccessDeniedHandler;
import fu.se184491.loadmaster_be.config.security.RestAuthenticationEntryPoint;
import fu.se184491.loadmaster_be.config.security.RolePermissionMapping;
import fu.se184491.loadmaster_be.config.security.WebSecurityConfig;
import fu.se184491.loadmaster_be.constant.support.ContactRequestType;
import fu.se184491.loadmaster_be.constant.support.ContactStatus;
import fu.se184491.loadmaster_be.dto.request.support.CreateContactOfGuestRequest;
import fu.se184491.loadmaster_be.dto.request.support.UpdateContactOfGuestRequest;
import fu.se184491.loadmaster_be.dto.response.support.ContactOfGuestResponse;
import fu.se184491.loadmaster_be.service.support.ContactOfGuestService;
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

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ContactGuestController.class)
@Import(WebSecurityConfig.class)
@AutoConfigureMockMvc(addFilters = false)
class ContactGuestControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ContactOfGuestService contactOfGuestService;

    @MockitoBean
    private JwtAuthenticationConverter jwtAuthenticationConverter;

    @MockitoBean
    private RolePermissionMapping rolePermissionMapping;

    @MockitoBean
    private RestAuthenticationEntryPoint authenticationEntryPoint;

    @MockitoBean
    private RestAccessDeniedHandler accessDeniedHandler;

    private ObjectMapper objectMapper;
    private ContactOfGuestResponse sampleResponse;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        sampleResponse = ContactOfGuestResponse.builder()
                .id(1L)
                .fullName("Nguyễn Khách")
                .email("khach@example.com")
                .phoneNumber("0987654321")
                .companyName("Công ty Vận tải ABC")
                .requestType(ContactRequestType.DEMO_REQUEST)
                .status(ContactStatus.NEW)
                .message("Muốn đăng ký dùng thử phần mềm")
                .createdAt(Instant.now())
                .build();
    }

    @Test
    @DisplayName("POST /api/contact-guests (Public) tạo yêu cầu tư vấn thành công -> 201 Created")
    void createContact_Public_Success() throws Exception {
        CreateContactOfGuestRequest request = CreateContactOfGuestRequest.builder()
                .fullName("Nguyễn Khách")
                .email("khach@example.com")
                .phoneNumber("0987654321")
                .companyName("Công ty Vận tải ABC")
                .requestType(ContactRequestType.DEMO_REQUEST)
                .message("Muốn đăng ký dùng thử phần mềm")
                .build();

        when(contactOfGuestService.createContact(any(CreateContactOfGuestRequest.class))).thenReturn(sampleResponse);

        mockMvc.perform(post("/api/contact-guests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1L))
                .andExpect(jsonPath("$.fullName").value("Nguyễn Khách"))
                .andExpect(jsonPath("$.email").value("khach@example.com"))
                .andExpect(jsonPath("$.status").value("NEW"));
    }

    @Test
    @DisplayName("POST /api/contact-guests sai định dạng email -> 400 Bad Request")
    void createContact_InvalidEmail_ReturnsBadRequest() throws Exception {
        CreateContactOfGuestRequest request = CreateContactOfGuestRequest.builder()
                .fullName("Nguyễn Khách")
                .email("not-an-email")
                .requestType(ContactRequestType.INQUIRY)
                .build();

        mockMvc.perform(post("/api/contact-guests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "SYSTEM_SUPPORTER")
    @DisplayName("GET /api/contact-guests xem danh sách yêu cầu liên hệ -> 200 OK")
    void getContacts_Authenticated_Success() throws Exception {
        when(contactOfGuestService.getContacts(null, null)).thenReturn(List.of(sampleResponse));

        mockMvc.perform(get("/api/contact-guests"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1L))
                .andExpect(jsonPath("$[0].fullName").value("Nguyễn Khách"));
    }

    @Test
    @WithMockUser(roles = "SYSTEM_MANAGER")
    @DisplayName("GET /api/contact-guests/{id} xem chi tiết yêu cầu liên hệ -> 200 OK")
    void getContactById_Success() throws Exception {
        when(contactOfGuestService.getContactById(1L)).thenReturn(sampleResponse);

        mockMvc.perform(get("/api/contact-guests/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1L))
                .andExpect(jsonPath("$.email").value("khach@example.com"));
    }

    @Test
    @WithMockUser(roles = "SYSTEM_SUPPORTER")
    @DisplayName("PATCH /api/contact-guests/{id} cập nhật trạng thái liên hệ -> 200 OK")
    void updateContact_Success() throws Exception {
        UpdateContactOfGuestRequest updateRequest = UpdateContactOfGuestRequest.builder()
                .status(ContactStatus.IN_PROGRESS)
                .note("Đang gọi điện thoại tư vấn")
                .build();

        sampleResponse.setStatus(ContactStatus.IN_PROGRESS);
        sampleResponse.setNote("Đang gọi điện thoại tư vấn");

        when(contactOfGuestService.updateContact(eq(1L), any(UpdateContactOfGuestRequest.class)))
                .thenReturn(sampleResponse);

        mockMvc.perform(patch("/api/contact-guests/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.note").value("Đang gọi điện thoại tư vấn"));
    }
}
