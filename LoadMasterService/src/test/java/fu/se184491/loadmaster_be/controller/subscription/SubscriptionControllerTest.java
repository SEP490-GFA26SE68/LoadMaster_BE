package fu.se184491.loadmaster_be.controller.subscription;

import com.fasterxml.jackson.databind.ObjectMapper;
import fu.se184491.loadmaster_be.config.security.WebSecurityConfig;
import fu.se184491.loadmaster_be.constant.billing.AlgorithmTier;
import fu.se184491.loadmaster_be.constant.billing.PaymentGateway;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionStatus;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionTier;
import fu.se184491.loadmaster_be.dto.request.subscription.SubscribeRequest;
import fu.se184491.loadmaster_be.dto.response.payment.PaymentUrlResponse;
import fu.se184491.loadmaster_be.dto.response.subscription.SubscriptionCurrentResponse;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.service.subscription.SubscriptionService;
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

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SubscriptionController.class)
@Import(WebSecurityConfig.class)
@AutoConfigureMockMvc(addFilters = false)
@WithMockUser(authorities = "COMPANY_ADMIN")
public class SubscriptionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SubscriptionService subscriptionService;

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
    @DisplayName("GET /api/subscription/current - Returns current subscription")
    void testGetCurrentSubscription() throws Exception {
        SubscriptionCurrentResponse response = SubscriptionCurrentResponse.builder()
                .subscriptionId(10L)
                .planId(2L)
                .planName("Pro Plan")
                .tier(SubscriptionTier.PRO)
                .status(SubscriptionStatus.ACTIVE)
                .startedAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusMonths(1))
                .autoRenew(true)
                .creditsBalance(150)
                .creditsMonthlyGrant(200)
                .algorithmTier(AlgorithmTier.EP_DBLF_GA)
                .build();

        when(subscriptionService.getCurrentSubscription(1L)).thenReturn(response);

        mockMvc.perform(get("/api/subscription/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.planName").value("Pro Plan"))
                .andExpect(jsonPath("$.data.creditsBalance").value(150));

        verify(subscriptionService).getCurrentSubscription(1L);
    }

    @Test
    @DisplayName("POST /api/subscription/subscribe - Triggers subscribe flow")
    void testSubscribe() throws Exception {
        SubscribeRequest request = SubscribeRequest.builder()
                .planId(2L)
                .gateway(PaymentGateway.VNPAY)
                .build();

        PaymentUrlResponse paymentResponse = PaymentUrlResponse.builder()
                .paymentUrl("https://sandbox.vnpayment.vn/vpcpay?...")
                .transactionId("VNP_123")
                .status("PENDING")
                .build();

        when(subscriptionService.subscribe(eq(1L), any(SubscribeRequest.class), any()))
                .thenReturn(paymentResponse);

        mockMvc.perform(post("/api/subscription/subscribe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.transactionId").value("VNP_123"));

        verify(subscriptionService).subscribe(eq(1L), any(SubscribeRequest.class), any());
    }

    @Test
    @DisplayName("POST /api/subscription/cancel - Cancels subscription")
    void testCancel() throws Exception {
        mockMvc.perform(post("/api/subscription/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(subscriptionService).cancelSubscription(1L);
    }

    @Test
    @DisplayName("GET /api/subscription/current - Allows ROLE_ADMIN")
    @WithMockUser(roles = "ADMIN")
    void testGetCurrentSubscription_WithRoleAdmin_ReturnsOk() throws Exception {
        when(subscriptionService.getCurrentSubscription(1L))
                .thenReturn(SubscriptionCurrentResponse.builder().subscriptionId(10L).planName("Pro Plan").build());

        mockMvc.perform(get("/api/subscription/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("GET /api/subscription/current - Allows ROLE_MANAGER")
    @WithMockUser(roles = "MANAGER")
    void testGetCurrentSubscription_WithRoleManager_ReturnsOk() throws Exception {
        when(subscriptionService.getCurrentSubscription(1L))
                .thenReturn(SubscriptionCurrentResponse.builder().subscriptionId(10L).planName("Pro Plan").build());

        mockMvc.perform(get("/api/subscription/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("GET /api/subscription/current - Allows ROLE_DISPATCHER")
    @WithMockUser(roles = "DISPATCHER")
    void testGetCurrentSubscription_WithRoleDispatcher_ReturnsOk() throws Exception {
        when(subscriptionService.getCurrentSubscription(1L))
                .thenReturn(SubscriptionCurrentResponse.builder().subscriptionId(10L).planName("Pro Plan").build());

        mockMvc.perform(get("/api/subscription/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("GET /api/subscription/current - Allows ROLE_SYSTEM_MANAGER")
    @WithMockUser(roles = "SYSTEM_MANAGER")
    void testGetCurrentSubscription_WithRoleSystemManager_ReturnsOk() throws Exception {
        when(subscriptionService.getCurrentSubscription(1L))
                .thenReturn(SubscriptionCurrentResponse.builder().subscriptionId(10L).planName("Pro Plan").build());

        mockMvc.perform(get("/api/subscription/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("GET /api/subscription/current - Denies ROLE_DRIVER with 403 Forbidden")
    @WithMockUser(roles = "DRIVER")
    void testGetCurrentSubscription_WithRoleDriver_ReturnsForbidden() throws Exception {
        mockMvc.perform(get("/api/subscription/current"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/subscription/subscribe - Allows ROLE_ADMIN")
    @WithMockUser(roles = "ADMIN")
    void testSubscribe_WithRoleAdmin_ReturnsOk() throws Exception {
        SubscribeRequest request = SubscribeRequest.builder()
                .planId(2L)
                .gateway(PaymentGateway.VNPAY)
                .build();

        PaymentUrlResponse paymentResponse = PaymentUrlResponse.builder()
                .paymentUrl("https://sandbox.vnpayment.vn/vpcpay?...")
                .transactionId("VNP_123")
                .status("PENDING")
                .build();

        when(subscriptionService.subscribe(eq(1L), any(SubscribeRequest.class), any()))
                .thenReturn(paymentResponse);

        mockMvc.perform(post("/api/subscription/subscribe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("POST /api/subscription/subscribe - Denies ROLE_DISPATCHER with 403 Forbidden")
    @WithMockUser(roles = "DISPATCHER")
    void testSubscribe_WithRoleDispatcher_ReturnsForbidden() throws Exception {
        SubscribeRequest request = SubscribeRequest.builder()
                .planId(2L)
                .gateway(PaymentGateway.VNPAY)
                .build();

        mockMvc.perform(post("/api/subscription/subscribe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/subscription/cancel - Allows ROLE_ADMIN")
    @WithMockUser(roles = "ADMIN")
    void testCancel_WithRoleAdmin_ReturnsOk() throws Exception {
        mockMvc.perform(post("/api/subscription/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(subscriptionService).cancelSubscription(1L);
    }

    @Test
    @DisplayName("POST /api/subscription/cancel - Denies ROLE_MANAGER with 403 Forbidden")
    @WithMockUser(roles = "MANAGER")
    void testCancel_WithRoleManager_ReturnsForbidden() throws Exception {
        mockMvc.perform(post("/api/subscription/cancel"))
                .andExpect(status().isForbidden());
    }
}
