package fu.se184491.loadmaster_be.controller.company;

import com.fasterxml.jackson.databind.ObjectMapper;
import fu.se184491.loadmaster_be.config.security.RolePermissionMapping;
import fu.se184491.loadmaster_be.config.security.WebSecurityConfig;
import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.account.UserStatus;
import fu.se184491.loadmaster_be.constant.company.AgreementStatus;
import fu.se184491.loadmaster_be.constant.company.AgreementType;
import fu.se184491.loadmaster_be.constant.company.CompanyStatus;
import fu.se184491.loadmaster_be.dto.request.company.SignAgreementRequest;
import fu.se184491.loadmaster_be.dto.response.company.CompanyAgreementResponse;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.exception.GlobalExceptionHandler;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.service.company.CompanyAgreementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.test.context.support.WithMockUser;

@WebMvcTest(CompanyAgreementController.class)
@Import(WebSecurityConfig.class)
@AutoConfigureMockMvc(addFilters = false)
@WithMockUser(roles = "ADMIN")
class CompanyAgreementControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private CompanyAgreementService agreementService;

    @MockitoBean
    private CurrentUserService currentUserService;

    @MockitoBean
    private JwtAuthenticationConverter jwtAuthenticationConverter;

    private User adminUser;
    private CompanyAgreementResponse mockResponse;

    @BeforeEach
    void setUp() {
        Company company = Company.builder()
                .id(1L)
                .companyCode("COMP-1")
                .companyName("Logistics One")
                .taxCode("0123456789")
                .status(CompanyStatus.ACTIVE)
                .build();

        adminUser = User.builder()
                .id(10L)
                .email("admin@logisticsone.vn")
                .fullName("Admin One")
                .phoneNumber("0912345678")
                .userRoleType(UserRole.ADMIN)
                .status(UserStatus.ACTIVE)
                .company(company)
                .build();

        mockResponse = CompanyAgreementResponse.builder()
                .id(100L)
                .companyId(1L)
                .companyName("Logistics One")
                .companyCode("COMP-1")
                .taxCode("0123456789")
                .userId(10L)
                .agreementType(AgreementType.TRIAL_TERMS)
                .agreementCode("AGR-TEST-100")
                .title("Thỏa thuận Sử dụng Dịch vụ Thử nghiệm LoadMaster")
                .content("Nội dung điều khoản...")
                .status(AgreementStatus.PENDING)
                .deadlineAt(LocalDateTime.now().plusDays(3))
                .isExpired(false)
                .hoursRemaining(72)
                .createdAt(Instant.now())
                .build();
    }

    @Test
    @DisplayName("GET /api/agreements/my-agreement: trả về thỏa thuận của Company Admin hiện tại")
    void getMyAgreement_success() throws Exception {
        when(currentUserService.getCurrentUser()).thenReturn(adminUser);
        when(agreementService.getMyAgreement(adminUser)).thenReturn(mockResponse);

        mockMvc.perform(get("/api/agreements/my-agreement"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(100))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.companyName").value("Logistics One"));
    }

    @Test
    @DisplayName("POST /api/agreements/sign: Company Admin ký thỏa thuận thành công")
    void signAgreement_success() throws Exception {
        when(currentUserService.getCurrentUser()).thenReturn(adminUser);

        CompanyAgreementResponse signedResponse = CompanyAgreementResponse.builder()
                .id(100L)
                .companyId(1L)
                .companyName("Logistics One")
                .status(AgreementStatus.SIGNED)
                .signedAt(LocalDateTime.now())
                .signerName("Admin One")
                .signerEmail("admin@logisticsone.vn")
                .isExpired(false)
                .hoursRemaining(0)
                .build();

        when(agreementService.signAgreement(eq(adminUser), any(SignAgreementRequest.class), any()))
                .thenReturn(signedResponse);

        SignAgreementRequest request = SignAgreementRequest.builder()
                .confirmTerms(true)
                .signerName("Admin One")
                .signerPhone("0912345678")
                .build();

        mockMvc.perform(post("/api/agreements/sign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("SIGNED"))
                .andExpect(jsonPath("$.data.signerName").value("Admin One"));
    }

    @Test
    @DisplayName("GET /api/agreements/company/{companyId}: Platform Admin xem thỏa thuận của công ty")
    @WithMockUser(roles = "SYSTEM_ADMIN")
    void getCompanyAgreement_success() throws Exception {
        when(agreementService.getCompanyAgreement(1L)).thenReturn(mockResponse);

        mockMvc.perform(get("/api/agreements/company/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.companyId").value(1))
                .andExpect(jsonPath("$.data.agreementCode").value("AGR-TEST-100"));
    }
}
