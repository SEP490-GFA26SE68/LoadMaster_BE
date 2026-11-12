package fu.se184491.loadmaster_be.controller.payment;

import fu.se184491.loadmaster_be.client.VnPayClient;
import fu.se184491.loadmaster_be.config.security.WebSecurityConfig;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.service.payment.MockCheckoutAccess;
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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    @MockitoBean
    private VnPayClient vnPayClient;

    @MockitoBean
    private MockCheckoutAccess mockCheckoutAccess;

    // FlaggedUserEnforcementFilter (WebSecurityConfig) cần bean này.
    @MockitoBean
    private CurrentUserService currentUserService;

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
    @DisplayName("GET /api/payment/mock-checkout - vnpay.mock off: refuses with 404 and confirms nothing")
    void testMockCheckoutRefusedWhenMockConfigOff() throws Exception {
        when(vnPayClient.isMock()).thenReturn(false);

        mockMvc.perform(get("/api/payment/mock-checkout")
                        .param("txnRef", "MOCK_123"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_TRANSACTION_NOT_FOUND"));

        verify(paymentCallbackHandler, never()).handleVnPayCallback(anyMap());
    }

    @Test
    @DisplayName("GET /api/payment/mock-checkout - vnpay.mock on: simulates mock payment without a vnp_Mock flag")
    void testMockCheckout() throws Exception {
        when(vnPayClient.isMock()).thenReturn(true);
        when(paymentCallbackHandler.handleVnPayCallback(anyMap()))
                .thenReturn(Map.of("RspCode", "00", "Message", "Confirm Success"));

        mockMvc.perform(get("/api/payment/mock-checkout")
                        .param("txnRef", "MOCK_123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.txnRef").value("MOCK_123"));

        verify(paymentCallbackHandler).handleVnPayCallback(argThat(params ->
                "MOCK_123".equals(params.get("vnp_TxnRef")) && !params.containsKey("vnp_Mock")));
    }

    @Test
    @DisplayName("GET /api/payment/mock-checkout - not signed in: 401, confirms nothing")
    void testMockCheckoutRequiresLogin() throws Exception {
        when(vnPayClient.isMock()).thenReturn(true);
        doThrow(new AppException(ErrorCode.UNAUTHENTICATED)).when(mockCheckoutAccess).requireAllowed("MOCK_123");

        mockMvc.perform(get("/api/payment/mock-checkout")
                        .param("txnRef", "MOCK_123"))
                .andExpect(status().isUnauthorized());

        verify(paymentCallbackHandler, never()).handleVnPayCallback(anyMap());
    }

    @Test
    @DisplayName("GET /api/payment/mock-checkout - transaction of another company: 404, confirms nothing")
    void testMockCheckoutOfAnotherCompanyIsNotFound() throws Exception {
        when(vnPayClient.isMock()).thenReturn(true);
        doThrow(new AppException(ErrorCode.PAYMENT_TRANSACTION_NOT_FOUND))
                .when(mockCheckoutAccess).requireAllowed("MOCK_OTHER");

        mockMvc.perform(get("/api/payment/mock-checkout")
                        .param("txnRef", "MOCK_OTHER"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_TRANSACTION_NOT_FOUND"));

        verify(paymentCallbackHandler, never()).handleVnPayCallback(anyMap());
    }

    @Test
    @DisplayName("GET /webhook/vnpay-return - Processes VNPay callback and redirects to FE /goi-cuoc")
    void testVnPayReturnRedirect() throws Exception {
        when(paymentCallbackHandler.handleVnPayCallback(anyMap()))
                .thenReturn(Map.of("RspCode", "00", "Message", "Confirm Success"));

        mockMvc.perform(get("/webhook/vnpay-return")
                        .param("vnp_TxnRef", "VNPAY_123")
                        .param("vnp_ResponseCode", "00"))
                .andExpect(status().is3xxRedirection());
    }
}
