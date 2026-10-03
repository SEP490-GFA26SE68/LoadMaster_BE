package fu.se184491.loadmaster_be.controller.credit;

import com.fasterxml.jackson.databind.ObjectMapper;
import fu.se184491.loadmaster_be.config.security.WebSecurityConfig;
import fu.se184491.loadmaster_be.constant.billing.CreditTransactionType;
import fu.se184491.loadmaster_be.constant.billing.PaymentGateway;
import fu.se184491.loadmaster_be.dto.request.credit.CreditTopupRequest;
import fu.se184491.loadmaster_be.dto.response.credit.CreditBalanceResponse;
import fu.se184491.loadmaster_be.dto.response.credit.CreditTransactionResponse;
import fu.se184491.loadmaster_be.dto.response.payment.PaymentUrlResponse;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.company.CompanyRepository;
import fu.se184491.loadmaster_be.service.credit.CreditService;
import fu.se184491.loadmaster_be.service.payment.PaymentGatewayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CreditController.class)
@Import(WebSecurityConfig.class)
@AutoConfigureMockMvc(addFilters = false)
@org.springframework.security.test.context.support.WithMockUser(authorities = "COMPANY_ADMIN")
public class CreditControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CreditService creditService;

    @MockitoBean
    private PaymentGatewayService paymentGatewayService;

    @MockitoBean
    private CompanyRepository companyRepository;

    @MockitoBean
    private CurrentUserService currentUserService;

    @MockitoBean
    private JwtAuthenticationConverter jwtAuthenticationConverter;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        when(currentUserService.getCurrentCompanyId()).thenReturn(1L);
    }

    @Test
    @DisplayName("GET /api/credits/balance - Returns credit balance")
    void testGetBalance() throws Exception {
        CreditBalanceResponse balance = CreditBalanceResponse.builder()
                .companyId(1L)
                .balance(75)
                .build();

        when(creditService.getBalance(1L)).thenReturn(balance);

        mockMvc.perform(get("/api/credits/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.balance").value(75));

        verify(creditService).getBalance(1L);
    }

    @Test
    @DisplayName("GET /api/credits/transactions - Returns paginated transaction history")
    void testGetTransactions() throws Exception {
        CreditTransactionResponse tx = CreditTransactionResponse.builder()
                .id(1L)
                .amount(-1)
                .type(CreditTransactionType.USAGE)
                .reference("JOB-123")
                .createdAt(LocalDateTime.now())
                .build();
        Page<CreditTransactionResponse> page = new PageImpl<>(List.of(tx));

        when(creditService.getTransactions(eq(1L), any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get("/api/credits/transactions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[0].reference").value("JOB-123"));

        verify(creditService).getTransactions(eq(1L), any(Pageable.class));
    }

    @Test
    @DisplayName("POST /api/credits/topup - Generates payment URL for credits topup")
    void testTopupCredits() throws Exception {
        Company company = Company.builder().id(1L).companyName("Acme").build();
        when(companyRepository.findById(1L)).thenReturn(Optional.of(company));

        CreditTopupRequest request = CreditTopupRequest.builder()
                .credits(50)
                .gateway(PaymentGateway.VNPAY)
                .build();

        PaymentUrlResponse paymentResponse = PaymentUrlResponse.builder()
                .paymentUrl("https://sandbox.vnpayment.vn/vpcpay?...")
                .transactionId("VNP_TOPUP_50")
                .status("PENDING")
                .build();

        when(paymentGatewayService.createPayment(eq(company), eq(PaymentGateway.VNPAY), eq("CREDIT_TOPUP"),
                eq(50L), eq(new BigDecimal("250000")), any(), any()))
                .thenReturn(paymentResponse);

        mockMvc.perform(post("/api/credits/topup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.transactionId").value("VNP_TOPUP_50"));
    }
}
