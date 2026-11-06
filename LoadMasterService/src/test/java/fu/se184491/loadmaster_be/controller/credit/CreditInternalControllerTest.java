package fu.se184491.loadmaster_be.controller.credit;

import fu.se184491.loadmaster_be.controller.internal.CreditInternalController;
import fu.se184491.loadmaster_be.security.TestJwtSupport;
import fu.se184491.loadmaster_be.service.credit.CreditService;
import fu.se184491.loadmaster_be.exception.InsufficientCreditsException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Instant;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({CreditInternalController.class, InternalCreditController.class})
@Import(TestJwtSupport.class)
class CreditInternalControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean CreditService creditService;

    private String body(String path) {
        return path.startsWith("/api/internal/")
                ? "{\"companyId\":1,\"reference\":\"job-123\"}"
                : "{\"company_id\":1,\"reference\":\"job-123\"}";
    }
    private void request(String path, String token, int expected) throws Exception {
        var request = post(path).contentType("application/json").content(body(path));
        if (token != null) request.header("Authorization", "Bearer " + token);
        mvc.perform(request).andExpect(status().is(expected));
    }
    private String service(String audience, String client, String username, List<String> roles) throws Exception {
        return TestJwtSupport.serviceToken(audience, client, username, roles);
    }

    @ParameterizedTest
    @ValueSource(strings={"/api/internal/credits/deduct","/api/internal/credits/refund","/api/credits/deduct","/api/credits/refund"})
    void anonymousIs401(String path) throws Exception {
        request(path, null, 401); verifyNoInteractions(creditService);
    }

    @ParameterizedTest
    @ValueSource(strings={"/api/internal/credits/deduct","/api/internal/credits/refund","/api/credits/deduct","/api/credits/refund"})
    void signedWrongAudienceIs401(String path) throws Exception {
        request(path, service("account", "optimization-service", "service-account-optimization-service", List.of("OPTIMIZATION_CREDIT")), 401);
        verifyNoInteractions(creditService);
    }

    @ParameterizedTest
    @ValueSource(strings={"/api/internal/credits/deduct","/api/internal/credits/refund","/api/credits/deduct","/api/credits/refund"})
    void missingRoleIs403(String path) throws Exception {
        request(path, service("loadmaster-api", "optimization-service", "service-account-optimization-service", List.of()), 403);
        verifyNoInteractions(creditService);
    }

    @ParameterizedTest
    @ValueSource(strings={"/api/internal/credits/deduct","/api/internal/credits/refund","/api/credits/deduct","/api/credits/refund"})
    void otherServiceEvenWithCreditRoleIs403(String path) throws Exception {
        request(path, service("loadmaster-api", "another-service", "service-account-another-service", List.of("OPTIMIZATION_CREDIT")), 403);
        verifyNoInteractions(creditService);
    }

    @ParameterizedTest
    @ValueSource(strings={"/api/internal/credits/deduct","/api/internal/credits/refund","/api/credits/deduct","/api/credits/refund"})
    void interactiveUserEvenWithMatchingClientAndRoleIs403(String path) throws Exception {
        request(path, service("loadmaster-api", "optimization-service", "human-user", List.of("OPTIMIZATION_CREDIT")), 403);
        verifyNoInteractions(creditService);
    }

    @ParameterizedTest
    @ValueSource(strings={"/api/internal/credits/deduct","/api/internal/credits/refund","/api/credits/deduct","/api/credits/refund"})
    void correctSignedServiceTokenIsAllowed(String path) throws Exception {
        request(path, service("loadmaster-api", "optimization-service", "service-account-optimization-service", List.of("OPTIMIZATION_CREDIT")), 200);
        if (path.endsWith("/deduct")) verify(creditService).deductCredit(1L,"job-123");
        else verify(creditService).refundCredit(1L,"job-123");
    }

    @Test void expiredSignedTokenIs401() throws Exception {
        request("/api/internal/credits/refund", TestJwtSupport.token("loadmaster-api", "optimization-service",
                "service-account-optimization-service", "service", List.of("OPTIMIZATION_CREDIT"),
                TestJwtSupport.ISSUER, Instant.now().minusSeconds(300)), 401);
        verifyNoInteractions(creditService);
    }
    @Test void wrongIssuerIs401() throws Exception {
        request("/api/internal/credits/refund", TestJwtSupport.token("loadmaster-api", "optimization-service",
                "service-account-optimization-service", "service", List.of("OPTIMIZATION_CREDIT"),
                "https://other-issuer.test", Instant.now().plusSeconds(300)), 401);
        verifyNoInteractions(creditService);
    }
    @Test void insufficientCreditStatusRemains402() throws Exception {
        doThrow(new InsufficientCreditsException()).when(creditService).deductCredit(1L,"job-123");
        request("/api/internal/credits/deduct", service("loadmaster-api", "optimization-service",
                "service-account-optimization-service", List.of("OPTIMIZATION_CREDIT")), 402);
    }
}
