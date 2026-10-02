package fu.se184491.loadmaster_be.controller.subscription;

import com.fasterxml.jackson.databind.ObjectMapper;
import fu.se184491.loadmaster_be.config.security.WebSecurityConfig;
import fu.se184491.loadmaster_be.constant.billing.AlgorithmTier;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionTier;
import fu.se184491.loadmaster_be.dto.request.subscription.SubscriptionPlanRequest;
import fu.se184491.loadmaster_be.dto.response.subscription.SubscriptionPlanResponse;
import fu.se184491.loadmaster_be.service.subscription.SubscriptionPlanService;
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
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SubscriptionPlanController.class)
@Import(WebSecurityConfig.class)
@AutoConfigureMockMvc(addFilters = false)
public class SubscriptionPlanControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SubscriptionPlanService planService;

    @MockitoBean
    private JwtAuthenticationConverter jwtAuthenticationConverter;

    private ObjectMapper objectMapper;
    private SubscriptionPlanRequest request;
    private SubscriptionPlanResponse response;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();

        request = SubscriptionPlanRequest.builder()
                .planCode("PLAN-PRO")
                .name("Pro Plan")
                .priceVnd(BigDecimal.valueOf(500000))
                .tier(SubscriptionTier.PRO)
                .monthlyCredits(500)
                .algorithmTier(AlgorithmTier.EP_DBLF_GA)
                .active(true)
                .build();

        response = SubscriptionPlanResponse.builder()
                .id(1L)
                .planCode("PLAN-PRO")
                .name("Pro Plan")
                .priceVnd(BigDecimal.valueOf(500000))
                .tier(SubscriptionTier.PRO)
                .monthlyCredits(500)
                .algorithmTier(AlgorithmTier.EP_DBLF_GA)
                .active(true)
                .build();
    }

    @Test
    @DisplayName("GET /api/subscription/plans should return active plans")
    void getActivePlans_success() throws Exception {
        when(planService.getActivePlans()).thenReturn(List.of(response));

        mockMvc.perform(get("/api/subscription/plans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1L))
                .andExpect(jsonPath("$[0].planCode").value("PLAN-PRO"));
    }

    @Test
    @DisplayName("POST /api/subscription/plans should return 201 Created")
    @WithMockUser(authorities = "SYSTEM_MANAGER")
    void createPlan_success() throws Exception {
        when(planService.createPlan(any(SubscriptionPlanRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/subscription/plans")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1L))
                .andExpect(jsonPath("$.tier").value("PRO"));
    }

    @Test
    @DisplayName("PUT /api/subscription/plans/{id} should return 200 OK")
    @WithMockUser(authorities = "SYSTEM_MANAGER")
    void updatePlan_success() throws Exception {
        when(planService.updatePlan(eq(1L), any(SubscriptionPlanRequest.class))).thenReturn(response);

        mockMvc.perform(put("/api/subscription/plans/1")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1L));
    }

    @Test
    @DisplayName("DELETE /api/subscription/plans/{id} should return 204 No Content")
    @WithMockUser(authorities = "SYSTEM_MANAGER")
    void deletePlan_success() throws Exception {
        doNothing().when(planService).deletePlan(1L);

        mockMvc.perform(delete("/api/subscription/plans/1")
                        .with(csrf()))
                .andExpect(status().isNoContent());

        verify(planService).deletePlan(1L);
    }
}
