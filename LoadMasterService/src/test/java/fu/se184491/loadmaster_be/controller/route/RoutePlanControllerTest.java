package fu.se184491.loadmaster_be.controller.route;

import com.fasterxml.jackson.databind.ObjectMapper;
import fu.se184491.loadmaster_be.config.security.RestAccessDeniedHandler;
import fu.se184491.loadmaster_be.config.security.RestAuthenticationEntryPoint;
import fu.se184491.loadmaster_be.config.security.RolePermissionMapping;
import fu.se184491.loadmaster_be.config.security.WebSecurityConfig;
import fu.se184491.loadmaster_be.dto.request.route.CreateRouteLegRequest;
import fu.se184491.loadmaster_be.dto.request.route.CreateRoutePlanRequest;
import fu.se184491.loadmaster_be.dto.request.route.CreateRouteSuggestionRequest;
import fu.se184491.loadmaster_be.dto.response.route.RouteLegResponse;
import fu.se184491.loadmaster_be.dto.response.route.RoutePlanDetailResponse;
import fu.se184491.loadmaster_be.dto.response.route.RoutePlanSummaryResponse;
import fu.se184491.loadmaster_be.dto.response.route.RouteSuggestionResponse;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.service.monitoring.TripLookupHelper;
import fu.se184491.loadmaster_be.service.route.RoutePlanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RoutePlanController.class)
@Import(WebSecurityConfig.class)
@AutoConfigureMockMvc(addFilters = false)
@WithMockUser(authorities = "DISPATCHER")
public class RoutePlanControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RoutePlanService routePlanService;

    @MockitoBean
    private CurrentUserService currentUserService;

    @MockitoBean
    private TripLookupHelper tripLookupHelper;

    @MockitoBean
    private JwtAuthenticationConverter jwtAuthenticationConverter;

    @MockitoBean
    private RolePermissionMapping rolePermissionMapping;

    @MockitoBean
    private RestAuthenticationEntryPoint authenticationEntryPoint;

    @MockitoBean
    private RestAccessDeniedHandler accessDeniedHandler;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        when(currentUserService.getCurrentCompanyId()).thenReturn(1L);
        when(tripLookupHelper.requireTrip(eq("10"), eq(1L)))
                .thenReturn(Trip.builder().id(10L).tripCode("TRIP-010").build());
    }

    @Test
    @DisplayName("POST /api/trips/{tripId}/route-plans - Returns 201 and ApiResponse")
    void testCreateRoutePlan() throws Exception {
        CreateRouteLegRequest leg = CreateRouteLegRequest.builder()
                .legSequence(1)
                .startStopId(101L)
                .endStopId(102L)
                .distanceMeters(5000)
                .durationSeconds(600)
                .build();

        CreateRoutePlanRequest request = CreateRoutePlanRequest.builder()
                .planVersion(1)
                .totalDistanceMeters(5000)
                .totalDurationSeconds(600)
                .legs(List.of(leg))
                .build();

        RoutePlanDetailResponse response = RoutePlanDetailResponse.builder()
                .id(100L)
                .tripId(10L)
                .planVersion(1)
                .totalDistanceMeters(5000)
                .totalDurationSeconds(600)
                .isActive(true)
                .createdAt(LocalDateTime.now())
                .legs(List.of(RouteLegResponse.builder().id(201L).legSequence(1).build()))
                .build();

        when(routePlanService.createRoutePlan(eq(1L), eq(10L), any(CreateRoutePlanRequest.class)))
                .thenReturn(response);

        mockMvc.perform(post("/api/trips/10/route-plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(100))
                .andExpect(jsonPath("$.data.tripId").value(10));
    }

    @Test
    @DisplayName("GET /api/trips/{tripId}/route-plans/active - Returns 200 and ApiResponse")
    void testGetActiveRoutePlan() throws Exception {
        RoutePlanDetailResponse response = RoutePlanDetailResponse.builder()
                .id(100L)
                .tripId(10L)
                .planVersion(1)
                .isActive(true)
                .build();

        when(routePlanService.getActiveRoutePlan(1L, 10L)).thenReturn(response);

        mockMvc.perform(get("/api/trips/10/route-plans/active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(100));
    }

    @Test
    @DisplayName("GET /api/trips/{tripId}/route-plans - Returns 200 and PageResponse")
    void testGetRoutePlans() throws Exception {
        RoutePlanSummaryResponse summary = RoutePlanSummaryResponse.builder()
                .id(100L)
                .tripId(10L)
                .planVersion(1)
                .isActive(true)
                .build();

        when(routePlanService.getRoutePlans(eq(1L), eq(10L), any()))
                .thenReturn(new PageImpl<>(List.of(summary), PageRequest.of(0, 10), 1));

        mockMvc.perform(get("/api/trips/10/route-plans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].id").value(100))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("GET /api/route-plans/{planId}/legs - Returns 200 and PageResponse")
    void testGetRouteLegs() throws Exception {
        RouteLegResponse leg = RouteLegResponse.builder()
                .id(201L)
                .routePlanId(100L)
                .legSequence(1)
                .distanceMeters(5000)
                .build();

        when(routePlanService.getRouteLegs(eq(1L), eq(100L), any()))
                .thenReturn(new PageImpl<>(List.of(leg), PageRequest.of(0, 10), 1));

        mockMvc.perform(get("/api/route-plans/100/legs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].id").value(201));
    }

    @Test
    @DisplayName("POST /api/route-legs/{legId}/suggestions - Returns 201 and ApiResponse")
    void testCreateSuggestion() throws Exception {
        CreateRouteSuggestionRequest req = CreateRouteSuggestionRequest.builder()
                .suggestionType("BYPASS")
                .durationDiffSeconds(120)
                .build();

        RouteSuggestionResponse response = RouteSuggestionResponse.builder()
                .id(301L)
                .routeLegId(201L)
                .suggestionType("BYPASS")
                .isAccepted(false)
                .build();

        when(routePlanService.createSuggestion(eq(1L), eq(201L), any()))
                .thenReturn(response);

        mockMvc.perform(post("/api/route-legs/201/suggestions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(301));
    }

    @Test
    @DisplayName("PATCH /api/route-suggestions/{id}/accept - Returns 200 and ApiResponse")
    void testAcceptSuggestion() throws Exception {
        RouteSuggestionResponse response = RouteSuggestionResponse.builder()
                .id(301L)
                .routeLegId(201L)
                .suggestionType("BYPASS")
                .isAccepted(true)
                .build();

        when(routePlanService.acceptSuggestion(1L, 301L)).thenReturn(response);

        mockMvc.perform(patch("/api/route-suggestions/301/accept"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.isAccepted").value(true));
    }
}
