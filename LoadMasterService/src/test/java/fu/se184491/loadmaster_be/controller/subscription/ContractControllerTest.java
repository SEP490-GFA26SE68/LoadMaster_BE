package fu.se184491.loadmaster_be.controller.subscription;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import fu.se184491.loadmaster_be.config.security.RestAccessDeniedHandler;
import fu.se184491.loadmaster_be.config.security.RestAuthenticationEntryPoint;
import fu.se184491.loadmaster_be.config.security.RolePermissionMapping;
import fu.se184491.loadmaster_be.config.security.WebSecurityConfig;
import fu.se184491.loadmaster_be.constant.billing.BillingCycle;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionStatus;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionTier;
import fu.se184491.loadmaster_be.dto.request.subscription.CreateContractRequest;
import fu.se184491.loadmaster_be.dto.request.subscription.UpdateContractRequest;
import fu.se184491.loadmaster_be.dto.response.subscription.ContractResponse;
import fu.se184491.loadmaster_be.service.subscription.ContractService;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ContractController.class)
@Import(WebSecurityConfig.class)
@AutoConfigureMockMvc(addFilters = false)
class ContractControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ContractService contractService;

    @MockitoBean
    private JwtAuthenticationConverter jwtAuthenticationConverter;

    @MockitoBean
    private RolePermissionMapping rolePermissionMapping;

    @MockitoBean
    private RestAuthenticationEntryPoint authenticationEntryPoint;

    @MockitoBean
    private RestAccessDeniedHandler accessDeniedHandler;

    private ObjectMapper objectMapper;
    private ContractResponse sampleResponse;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        sampleResponse = ContractResponse.builder()
                .id(1L)
                .companyId(10L)
                .companyCode("COMP-10")
                .companyName("Công ty Vận Tải An Phát")
                .planId(5L)
                .planCode("PLAN_PRO_YEARLY")
                .planName("Gói Chuyên Nghiệp Năm")
                .planTier(SubscriptionTier.PRO)
                .price(BigDecimal.valueOf(60000000))
                .billingCycle(BillingCycle.YEARLY)
                .status(SubscriptionStatus.ACTIVE)
                .startedAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusYears(1))
                .autoRenew(true)
                .build();
    }

    @Test
    @WithMockUser(roles = "SYSTEM_MANAGER")
    @DisplayName("GET /api/contracts xem danh sách hợp đồng -> 200 OK")
    void getContracts_Success() throws Exception {
        when(contractService.getContracts(null, null, null)).thenReturn(List.of(sampleResponse));

        mockMvc.perform(get("/api/contracts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1L))
                .andExpect(jsonPath("$[0].companyName").value("Công ty Vận Tải An Phát"));
    }

    @Test
    @WithMockUser(roles = "SYSTEM_MANAGER")
    @DisplayName("GET /api/contracts/{id} xem chi tiết hợp đồng -> 200 OK")
    void getContractById_Success() throws Exception {
        when(contractService.getContractById(1L)).thenReturn(sampleResponse);

        mockMvc.perform(get("/api/contracts/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1L))
                .andExpect(jsonPath("$.companyCode").value("COMP-10"));
    }

    @Test
    @WithMockUser(roles = "SYSTEM_MANAGER")
    @DisplayName("POST /api/contracts tạo/kích hoạt hợp đồng -> 201 Created")
    void createContract_Success() throws Exception {
        CreateContractRequest request = CreateContractRequest.builder()
                .companyId(10L)
                .planId(5L)
                .autoRenew(true)
                .billingPeriod("2026-YEARLY")
                .build();

        when(contractService.createOrActivateContract(any(CreateContractRequest.class)))
                .thenReturn(sampleResponse);

        mockMvc.perform(post("/api/contracts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1L))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    @WithMockUser(roles = "SYSTEM_MANAGER")
    @DisplayName("PATCH /api/contracts/{id} cập nhật hợp đồng -> 200 OK")
    void updateContract_Success() throws Exception {
        UpdateContractRequest updateRequest = UpdateContractRequest.builder()
                .status(SubscriptionStatus.CANCELLED)
                .autoRenew(false)
                .build();

        sampleResponse.setStatus(SubscriptionStatus.CANCELLED);
        sampleResponse.setAutoRenew(false);

        when(contractService.updateContract(eq(1L), any(UpdateContractRequest.class)))
                .thenReturn(sampleResponse);

        mockMvc.perform(patch("/api/contracts/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.autoRenew").value(false));
    }
}
