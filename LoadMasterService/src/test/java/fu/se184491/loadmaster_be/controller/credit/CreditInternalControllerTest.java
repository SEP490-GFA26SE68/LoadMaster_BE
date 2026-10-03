package fu.se184491.loadmaster_be.controller.credit;

import com.fasterxml.jackson.databind.ObjectMapper;
import fu.se184491.loadmaster_be.config.security.WebSecurityConfig;
import fu.se184491.loadmaster_be.controller.internal.CreditInternalController;
import fu.se184491.loadmaster_be.dto.request.credit.CreditInternalRequest;
import fu.se184491.loadmaster_be.exception.InsufficientCreditsException;
import fu.se184491.loadmaster_be.service.credit.CreditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CreditInternalController.class)
@Import(WebSecurityConfig.class)
@AutoConfigureMockMvc(addFilters = false)
public class CreditInternalControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CreditService creditService;

    @MockitoBean
    private JwtAuthenticationConverter jwtAuthenticationConverter;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("POST /api/internal/credits/deduct - Deduct credit success")
    void testDeductCreditSuccess() throws Exception {
        CreditInternalRequest request = CreditInternalRequest.builder()
                .companyId(1L)
                .reference("job-123")
                .build();

        doNothing().when(creditService).deductCredit(1L, "job-123");

        mockMvc.perform(post("/api/internal/credits/deduct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Credit deducted successfully"));

        verify(creditService).deductCredit(1L, "job-123");
    }

    @Test
    @DisplayName("POST /api/internal/credits/deduct - Insufficient credits returns 402")
    void testDeductCreditInsufficientReturns402() throws Exception {
        CreditInternalRequest request = CreditInternalRequest.builder()
                .companyId(1L)
                .reference("job-123")
                .build();

        doThrow(new InsufficientCreditsException(1L, 0))
                .when(creditService).deductCredit(1L, "job-123");

        mockMvc.perform(post("/api/internal/credits/deduct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.success").value(false));

        verify(creditService).deductCredit(1L, "job-123");
    }

    @Test
    @DisplayName("POST /api/internal/credits/refund - Refund credit success")
    void testRefundCreditSuccess() throws Exception {
        CreditInternalRequest request = CreditInternalRequest.builder()
                .reference("job-123")
                .build();

        doNothing().when(creditService).refundCredit(null, "job-123");

        mockMvc.perform(post("/api/internal/credits/refund")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Credit refunded successfully"));

        verify(creditService).refundCredit(null, "job-123");
    }
}
