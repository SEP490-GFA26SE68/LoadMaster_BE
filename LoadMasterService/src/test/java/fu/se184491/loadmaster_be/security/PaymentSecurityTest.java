package fu.se184491.loadmaster_be.security;

import fu.se184491.loadmaster_be.controller.payment.PaymentWebhookController;
import fu.se184491.loadmaster_be.client.VnPayClient;
import fu.se184491.loadmaster_be.config.payment.VnPayConfig;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.entity.billing.PaymentTransaction;
import fu.se184491.loadmaster_be.repository.billing.PaymentTransactionRepository;
import fu.se184491.loadmaster_be.service.payment.MockPaymentPolicy;
import fu.se184491.loadmaster_be.service.payment.PaymentCallbackHandler;
import fu.se184491.loadmaster_be.service.credit.CreditService;
import fu.se184491.loadmaster_be.service.subscription.SubscriptionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PaymentWebhookController.class)
@Import({TestJwtSupport.class, PaymentSecurityTest.Config.class, MockPaymentPolicy.class,
         PaymentCallbackHandler.class, VnPayClient.class})
@ActiveProfiles("prod")
class PaymentSecurityTest {
    @TestConfiguration(proxyBeanMethods=false)
    static class Config {
        @Bean VnPayConfig vnPayConfig() {
            VnPayConfig config = new VnPayConfig();
            config.setMock(true); // Even an accidentally enabled flag cannot enable mocks in production.
            config.setHashSecret("test-only-ephemeral-config-not-a-production-secret");
            return config;
        }
    }
    @Autowired MockMvc mvc;
    @Autowired VnPayClient vnPayClient;
    @MockitoBean PaymentTransactionRepository transactions;
    @MockitoBean CreditService credits;
    @MockitoBean SubscriptionService subscriptions;
    @MockitoBean CurrentUserService currentUser;

    @Test void mockCheckoutNeedsAuthentication() throws Exception {
        mvc.perform(get("/api/payment/mock-checkout").param("txnRef","MOCK_123"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(transactions,credits,subscriptions);
    }
    @Test void productionRejectsMockCheckoutEvenForCompanyAdminAndTrueFlag() throws Exception {
        mvc.perform(get("/api/payment/mock-checkout").param("txnRef","MOCK_123").param("vnp_Mock","true")
                .header("Authorization","Bearer "+TestJwtSupport.userToken("admin","ADMIN")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(transactions,credits,subscriptions);
    }
    @Test void ordinaryDispatcherCannotUseMockCheckout() throws Exception {
        mvc.perform(get("/api/payment/mock-checkout").param("txnRef","MOCK_123")
                .header("Authorization","Bearer "+TestJwtSupport.userToken("dispatcher","DISPATCHER")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(transactions,credits,subscriptions);
    }
    @Test void publicCallbackCannotEnableMockUsingFlagOrPrefix() throws Exception {
        mvc.perform(post("/webhook/vnpay").param("vnp_TxnRef","MOCK_123")
                .param("vnp_ResponseCode","00").param("vnp_Mock","true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.RspCode").value("97"));
        verifyNoInteractions(transactions,credits,subscriptions);
    }
    @Test void legitimatelySignedGatewayCallbackRemainsPublic() throws Exception {
        var txn = PaymentTransaction.builder().gatewayTransactionId("SIGNED_TEST")
                .amountVnd(BigDecimal.valueOf(100)).build();
        var request = get("/webhook/vnpay");
        String query = URI.create(vnPayClient.createPaymentUrl(txn,"test","127.0.0.1")).getRawQuery();
        for (String pair : query.split("&")) {
            String[] entry = pair.split("=",2);
            request.param(URLDecoder.decode(entry[0], StandardCharsets.UTF_8),
                          URLDecoder.decode(entry[1], StandardCharsets.UTF_8));
        }
        mvc.perform(request).andExpect(status().isOk()).andExpect(jsonPath("$.RspCode").value("01"));
        verify(transactions).findByGatewayTransactionId("SIGNED_TEST");
        verifyNoInteractions(credits,subscriptions);
    }
}
