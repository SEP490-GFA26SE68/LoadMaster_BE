package fu.se184491.loadmaster_be.controller.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import fu.se184491.loadmaster_be.config.security.RestAccessDeniedHandler;
import fu.se184491.loadmaster_be.config.security.RestAuthenticationEntryPoint;
import fu.se184491.loadmaster_be.config.security.RolePermissionMapping;
import fu.se184491.loadmaster_be.config.security.WebSecurityConfig;
import fu.se184491.loadmaster_be.constant.billing.InvoiceStatus;
import fu.se184491.loadmaster_be.dto.response.billing.InvoiceResponse;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.service.billing.InvoiceService;
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
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(InvoiceController.class)
@Import(WebSecurityConfig.class)
@AutoConfigureMockMvc(addFilters = false)
class InvoiceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InvoiceService invoiceService;

    @MockitoBean
    private CurrentUserService currentUserService;

    @MockitoBean
    private JwtAuthenticationConverter jwtAuthenticationConverter;

    @MockitoBean
    private RolePermissionMapping rolePermissionMapping;

    @MockitoBean
    private RestAuthenticationEntryPoint authenticationEntryPoint;

    @MockitoBean
    private RestAccessDeniedHandler accessDeniedHandler;

    private ObjectMapper objectMapper;
    private InvoiceResponse sampleInvoice;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        sampleInvoice = InvoiceResponse.builder()
                .id(1L)
                .invoiceNumber("INV-20261015-0001")
                .companyId(10L)
                .companyName("Công ty An Phát")
                .companySubscriptionId(5L)
                .planName("Gói Pro")
                .amount(BigDecimal.valueOf(5000000))
                .status(InvoiceStatus.PAID)
                .billingPeriod("2026-10")
                .createdAt(Instant.now())
                .paidAt(LocalDateTime.now())
                .build();

        when(currentUserService.getCurrentCompanyId()).thenReturn(10L);
    }

    @Test
    @DisplayName("GET /api/company/invoices should return invoices for company")
    @WithMockUser(roles = "ADMIN")
    void getCompanyInvoices_success() throws Exception {
        when(invoiceService.getCompanyInvoices(eq(10L), isNull())).thenReturn(List.of(sampleInvoice));

        mockMvc.perform(get("/api/company/invoices")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].invoiceNumber").value("INV-20261015-0001"))
                .andExpect(jsonPath("$[0].companyId").value(10))
                .andExpect(jsonPath("$[0].status").value("PAID"));
    }

    @Test
    @DisplayName("GET /api/company/invoices with status filter should pass status")
    @WithMockUser(roles = "ADMIN")
    void getCompanyInvoices_withStatus() throws Exception {
        when(invoiceService.getCompanyInvoices(eq(10L), eq(InvoiceStatus.PAID))).thenReturn(List.of(sampleInvoice));

        mockMvc.perform(get("/api/company/invoices")
                        .param("status", "PAID")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].status").value("PAID"));
    }

    @Test
    @DisplayName("GET /api/company/invoices/{id} should return invoice detail")
    @WithMockUser(roles = "ADMIN")
    void getInvoiceById_success() throws Exception {
        when(invoiceService.getInvoiceById(eq(1L), eq(10L), eq(false))).thenReturn(sampleInvoice);

        mockMvc.perform(get("/api/company/invoices/1")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.invoiceNumber").value("INV-20261015-0001"))
                .andExpect(jsonPath("$.amount").value(5000000));
    }

    @Test
    @DisplayName("GET /api/company/invoices/{id} should return 404 when invoice not found")
    @WithMockUser(roles = "ADMIN")
    void getInvoiceById_notFound() throws Exception {
        when(invoiceService.getInvoiceById(eq(999L), eq(10L), eq(false)))
                .thenThrow(new AppException(ErrorCode.INVOICE_NOT_FOUND));

        mockMvc.perform(get("/api/company/invoices/999")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }
}
