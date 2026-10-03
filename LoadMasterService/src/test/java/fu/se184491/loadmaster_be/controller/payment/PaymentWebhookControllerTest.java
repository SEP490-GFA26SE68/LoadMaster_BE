package fu.se184491.loadmaster_be.controller.payment;

import fu.se184491.loadmaster_be.config.security.WebSecurityConfig;
import fu.se184491.loadmaster_be.service.payment.PaymentCallbackHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PaymentWebhookController.class)
@Import(WebSecurityConfig.class)
@AutoConfigureMockMvc(addFilters = false)
public class PaymentWebhookControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentCallbackHandler paymentCallbackHandler;

    @MockitoBean
    private JwtAuthenticationConverter jwtAuthenticationConverter;

    @Test
    @DisplayName("POST /webhook/vnpay - Returns callback response")
    void testPostVnPayWebhook() throws Exception {
        when(paymentCallbackHandler.handleVnPayCallback(anyMap()))
                .thenReturn(Map.of("RspCode", "00", "Message", "Confirm Success"));

        mockMvc.perform(post("/webhook/vnpay")
                        .param("vnp_TxnRef", "VNP_123")
                        .param("vnp_ResponseCode", "00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.RspCode").value("00"))
                .andExpect(jsonPath("$.Message").value("Confirm Success"));
    }

    @Test
    @DisplayName("GET /api/payment/mock-checkout - Simulates mock payment")
    void testMockCheckout() throws Exception {
        when(paymentCallbackHandler.handleVnPayCallback(anyMap()))
                .thenReturn(Map.of("RspCode", "00", "Message", "Confirm Success"));

        mockMvc.perform(get("/api/payment/mock-checkout")
                        .param("txnRef", "MOCK_123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.txnRef").value("MOCK_123"));
    }
}
