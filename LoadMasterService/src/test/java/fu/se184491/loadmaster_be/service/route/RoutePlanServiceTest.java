package fu.se184491.loadmaster_be.service.route;

import fu.se184491.loadmaster_be.dto.request.route.CreateRouteLegRequest;
import fu.se184491.loadmaster_be.dto.request.route.CreateRoutePlanRequest;
import fu.se184491.loadmaster_be.dto.request.route.CreateRouteSuggestionRequest;
import fu.se184491.loadmaster_be.dto.response.route.RouteLegResponse;
import fu.se184491.loadmaster_be.dto.response.route.RoutePlanDetailResponse;
import fu.se184491.loadmaster_be.dto.response.route.RoutePlanSummaryResponse;
import fu.se184491.loadmaster_be.dto.response.route.RouteSuggestionResponse;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.route.RouteLeg;
import fu.se184491.loadmaster_be.entity.route.RoutePlan;
import fu.se184491.loadmaster_be.entity.route.RouteSuggestion;
import fu.se184491.loadmaster_be.entity.trip.DeliveryStop;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.route.RouteLegRepository;
import fu.se184491.loadmaster_be.repository.route.RoutePlanRepository;
import fu.se184491.loadmaster_be.repository.route.RouteSuggestionRepository;
import fu.se184491.loadmaster_be.repository.trip.DeliveryStopRepository;
import fu.se184491.loadmaster_be.repository.trip.TripRepository;
import fu.se184491.loadmaster_be.service.route.impl.RoutePlanServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class RoutePlanServiceTest {

    @Mock
    private RoutePlanRepository routePlanRepository;

    @Mock
    private RouteLegRepository routeLegRepository;

    @Mock
    private RouteSuggestionRepository routeSuggestionRepository;

    @Mock
    private TripRepository tripRepository;

    @Mock
    private DeliveryStopRepository deliveryStopRepository;

    @InjectMocks
    private RoutePlanServiceImpl routePlanService;

    private Company testCompany;
    private Trip testTrip;
    private DeliveryStop stop1;
    private DeliveryStop stop2;

    @BeforeEach
    void setUp() {
        testCompany = Company.builder().id(1L).companyName("Test Logistics").build();
        testTrip = Trip.builder().id(10L).company(testCompany).tripCode("TRIP-001").build();
        stop1 = DeliveryStop.builder().id(101L).trip(testTrip).stopSequence(1).build();
        stop2 = DeliveryStop.builder().id(102L).trip(testTrip).stopSequence(2).build();
    }

    @Test
    @DisplayName("createRoutePlan: Should deactivate old plans, persist new plan and legs successfully")
    void testCreateRoutePlan_Success() {
        CreateRouteLegRequest legReq = CreateRouteLegRequest.builder()
                .legSequence(1)
                .startStopId(101L)
                .endStopId(102L)
                .distanceMeters(5000)
                .durationSeconds(600)
                .polyline("enc_poly_1")
                .build();

        CreateRoutePlanRequest req = CreateRoutePlanRequest.builder()
                .planVersion(1)
                .totalDistanceMeters(5000)
                .totalDurationSeconds(600)
                .legs(List.of(legReq))
                .build();

        when(tripRepository.findByIdAndCompanyId(10L, 1L)).thenReturn(Optional.of(testTrip));
        when(deliveryStopRepository.findById(101L)).thenReturn(Optional.of(stop1));
        when(deliveryStopRepository.findById(102L)).thenReturn(Optional.of(stop2));

        RoutePlan savedPlan = RoutePlan.builder()
                .id(100L)
                .trip(testTrip)
                .planVersion(1)
                .totalDistanceMeters(5000)
                .totalDurationSeconds(600)
                .isActive(true)
                .createdAt(LocalDateTime.now())
                .build();

        when(routePlanRepository.save(any(RoutePlan.class))).thenReturn(savedPlan);

        RouteLeg savedLeg = RouteLeg.builder()
                .id(201L)
                .routePlan(savedPlan)
                .legSequence(1)
                .startStop(stop1)
                .endStop(stop2)
                .distanceMeters(5000)
                .durationSeconds(600)
                .polyline("enc_poly_1")
                .build();

        when(routeLegRepository.saveAll(any())).thenReturn(List.of(savedLeg));

        RoutePlanDetailResponse response = routePlanService.createRoutePlan(1L, 10L, req);

        assertNotNull(response);
        assertEquals(100L, response.getId());
        assertEquals(1, response.getLegs().size());
        verify(routePlanRepository).deactivateAllByTripId(10L);
    }

    @Test
    @DisplayName("createRoutePlan: Should throw TRIP_NOT_FOUND when trip does not exist for company")
    void testCreateRoutePlan_TripNotFound() {
        CreateRoutePlanRequest req = CreateRoutePlanRequest.builder()
                .legs(Collections.emptyList())
                .build();

        when(tripRepository.findByIdAndCompanyId(999L, 1L)).thenReturn(Optional.empty());

        AppException ex = assertThrows(AppException.class, () ->
                routePlanService.createRoutePlan(1L, 999L, req)
        );

        assertEquals(ErrorCode.TRIP_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    @DisplayName("getActiveRoutePlan: Should return active plan with sorted legs")
    void testGetActiveRoutePlan_Success() {
        RoutePlan plan = RoutePlan.builder()
                .id(100L)
                .trip(testTrip)
                .planVersion(2)
                .isActive(true)
                .createdAt(LocalDateTime.now())
                .build();

        RouteLeg leg = RouteLeg.builder()
                .id(201L)
                .routePlan(plan)
                .legSequence(1)
                .startStop(stop1)
                .endStop(stop2)
                .distanceMeters(3000)
                .durationSeconds(400)
                .build();

        when(routePlanRepository.findByTripIdAndTripCompanyIdAndIsActiveTrue(10L, 1L)).thenReturn(Optional.of(plan));
        when(routeLegRepository.findByRoutePlanIdOrderByLegSequenceAsc(100L)).thenReturn(List.of(leg));

        RoutePlanDetailResponse response = routePlanService.getActiveRoutePlan(1L, 10L);

        assertNotNull(response);
        assertEquals(100L, response.getId());
        assertEquals(1, response.getLegs().size());
        assertEquals(201L, response.getLegs().get(0).getId());
    }

    @Test
    @DisplayName("getActiveRoutePlan: Should throw ROUTE_PLAN_NOT_FOUND when no active plan exists")
    void testGetActiveRoutePlan_NotFound() {
        when(routePlanRepository.findByTripIdAndTripCompanyIdAndIsActiveTrue(10L, 1L)).thenReturn(Optional.empty());

        AppException ex = assertThrows(AppException.class, () ->
                routePlanService.getActiveRoutePlan(1L, 10L)
        );

        assertEquals(ErrorCode.ROUTE_PLAN_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    @DisplayName("getRoutePlans: Should return paged list of route plans")
    void testGetRoutePlans_Paged() {
        Pageable pageable = PageRequest.of(0, 10);
        RoutePlan plan = RoutePlan.builder().id(100L).trip(testTrip).planVersion(1).build();
        Page<RoutePlan> page = new PageImpl<>(List.of(plan), pageable, 1);

        when(routePlanRepository.findByTripIdAndTripCompanyIdOrderByPlanVersionDesc(10L, 1L, pageable))
                .thenReturn(page);

        Page<RoutePlanSummaryResponse> result = routePlanService.getRoutePlans(1L, 10L, pageable);

        assertNotNull(result);
        assertEquals(1, result.getTotalElements());
        assertEquals(100L, result.getContent().get(0).getId());
    }

    @Test
    @DisplayName("createSuggestion: Should persist and return suggestion for valid leg")
    void testCreateSuggestion_Success() {
        RoutePlan plan = RoutePlan.builder().id(100L).trip(testTrip).build();
        RouteLeg leg = RouteLeg.builder().id(201L).routePlan(plan).legSequence(1).build();

        when(routeLegRepository.findByIdAndRoutePlanTripCompanyId(201L, 1L)).thenReturn(Optional.of(leg));

        CreateRouteSuggestionRequest req = CreateRouteSuggestionRequest.builder()
                .suggestionType("BYPASS")
                .durationDiffSeconds(120)
                .distanceDiffMeters(500)
                .proposedPolyline("new_poly")
                .build();

        RouteSuggestion saved = RouteSuggestion.builder()
                .id(301L)
                .routeLeg(leg)
                .suggestionType("BYPASS")
                .durationDiffSeconds(120)
                .distanceDiffMeters(500)
                .isAccepted(false)
                .createdAt(LocalDateTime.now())
                .build();

        when(routeSuggestionRepository.save(any(RouteSuggestion.class))).thenReturn(saved);

        RouteSuggestionResponse response = routePlanService.createSuggestion(1L, 201L, req);

        assertNotNull(response);
        assertEquals(301L, response.getId());
        assertEquals("BYPASS", response.getSuggestionType());
        assertFalse(response.getIsAccepted());
    }

    @Test
    @DisplayName("acceptSuggestion: Should mark suggestion as accepted")
    void testAcceptSuggestion_Success() {
        RoutePlan plan = RoutePlan.builder().id(100L).trip(testTrip).build();
        RouteLeg leg = RouteLeg.builder().id(201L).routePlan(plan).build();
        RouteSuggestion suggestion = RouteSuggestion.builder()
                .id(301L)
                .routeLeg(leg)
                .suggestionType("BYPASS")
                .isAccepted(false)
                .build();

        when(routeSuggestionRepository.findByIdAndRouteLegRoutePlanTripCompanyId(301L, 1L))
                .thenReturn(Optional.of(suggestion));
        when(routeSuggestionRepository.save(any(RouteSuggestion.class))).thenAnswer(i -> i.getArgument(0));

        RouteSuggestionResponse response = routePlanService.acceptSuggestion(1L, 301L);

        assertNotNull(response);
        assertTrue(response.getIsAccepted());
    }
}
