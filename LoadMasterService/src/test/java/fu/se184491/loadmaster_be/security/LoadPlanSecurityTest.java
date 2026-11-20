package fu.se184491.loadmaster_be.security;

import fu.se184491.loadmaster_be.controller.optimization.LoadPlanController;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.entity.account.User;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.entity.optimization.*;
import fu.se184491.loadmaster_be.constant.optimization.OptimizationAlgorithm;
import fu.se184491.loadmaster_be.dto.response.optimization.OptimizationResultDto;
import fu.se184491.loadmaster_be.repository.account.UserRepository;
import fu.se184491.loadmaster_be.repository.optimization.*;
import fu.se184491.loadmaster_be.repository.cargo.CargoPackageRepository;
import fu.se184491.loadmaster_be.repository.common.AuditLogRepository;
import fu.se184491.loadmaster_be.service.optimization.Impl.LoadPlanServiceImpl;
import fu.se184491.loadmaster_be.service.optimization.OptimizationEngineClient;
import fu.se184491.loadmaster_be.service.credit.CreditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import java.util.Optional;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(LoadPlanController.class)
@Import({TestJwtSupport.class, LoadPlanServiceImpl.class, fu.se184491.loadmaster_be.service.optimization.Impl.LoadPlanQueryServiceImpl.class, CurrentUserService.class})
class LoadPlanSecurityTest {
    @MockitoBean fu.se184491.loadmaster_be.repository.optimization.LoadPlanWriteRepository writer;
    @Autowired MockMvc mvc;
    @MockitoBean fu.se184491.loadmaster_be.repository.optimization.LoadPlanReadRepository read;
    @MockitoBean fu.se184491.loadmaster_be.repository.trip.TripRepository trips;
    @MockitoBean fu.se184491.loadmaster_be.repository.trip.DeliveryStopRepository stops;
    @MockitoBean fu.se184491.loadmaster_be.repository.billing.CompanySubscriptionRepository subscriptions;
    @MockitoBean org.springframework.transaction.PlatformTransactionManager transactions;
    @MockitoBean fu.se184491.loadmaster_be.service.optimization.manual.StopZoneResolver zones;
    @MockitoBean fu.se184491.loadmaster_be.service.optimization.Impl.InterruptedOptimizationJobService interrupted;
    @MockitoBean fu.se184491.loadmaster_be.client.OptimizationClient client;
    @MockitoBean LoadPlanRepository plans;
    @MockitoBean PackagePlacementRepository placements;
    @MockitoBean OptimizationJobRepository jobs;
    @MockitoBean UnplacedPackageRepository unplaced;
    @MockitoBean AuditLogRepository audit;
    @MockitoBean CargoPackageRepository packages;
    @MockitoBean UserRepository users;
    @MockitoBean OptimizationEngineClient engine;
    @MockitoBean CreditService credits;
    @MockitoBean fu.se184491.loadmaster_be.service.optimization.OptimizationEntitlementService entitlements;

    @BeforeEach void setup() {
        when(subscriptions.findByCompanyId(anyLong())).thenReturn(Optional.of(
                fu.se184491.loadmaster_be.entity.billing.CompanySubscription.builder()
                    .status(fu.se184491.loadmaster_be.constant.billing.SubscriptionStatus.ACTIVE)
                    .expiresAt(java.time.LocalDateTime.now().plusDays(1)).build()));

        when(read.findPlan(47L)).thenReturn(Optional.of(new fu.se184491.loadmaster_be.repository.optimization.LoadPlanReadRepository.PlanWithCompany(fu.se184491.loadmaster_be.dto.response.optimization.LoadPlanDetailResponse.builder().id(47L).tripId(7L).build(),1L)));
        when(read.findPlan(48L)).thenReturn(Optional.of(new fu.se184491.loadmaster_be.repository.optimization.LoadPlanReadRepository.PlanWithCompany(fu.se184491.loadmaster_be.dto.response.optimization.LoadPlanDetailResponse.builder().id(48L).tripId(8L).build(),2L)));
        Company company = Company.builder().id(1L).build();
        when(users.findByKeycloakId("own-user")).thenReturn(Optional.of(User.builder().company(company).build()));
        when(users.findByKeycloakId("other-user")).thenReturn(Optional.of(User.builder().company(Company.builder().id(2L).build()).build()));
        OptimizationJob job = OptimizationJob.builder().id(1L).algorithmName(OptimizationAlgorithm.EP_DBLF)
                .trip(Trip.builder().company(company).status(fu.se184491.loadmaster_be.constant.trip.TripStatus.PLANNED).build()).build();
        when(plans.findById(47L)).thenReturn(Optional.of(LoadPlan.builder().id(47L).version(1).job(job).build()));
        when(plans.findById(48L)).thenReturn(Optional.of(LoadPlan.builder().id(48L).version(1)
                .job(OptimizationJob.builder().trip(Trip.builder().company(Company.builder().id(2L).build()).build()).build()).build()));
    }
    private void rerun(String token, int expected) throws Exception {
        var request = post("/api/load-plans/47/rerun").contentType("application/json").content("{}");
        if (token != null) request.header("Authorization", "Bearer " + token);
        mvc.perform(request).andExpect(status().is(expected));
    }
    @Test void anonymousCannotRerun() throws Exception {
        rerun(null,401); verifyNoInteractions(credits,engine,jobs);
    }
    @Test void managerCannotRerunEvenOwnPlan() throws Exception {
        rerun(TestJwtSupport.userToken("own-user","MANAGER"),403); verifyNoInteractions(credits,engine,jobs);
    }
    @Test void dispatcherFromAnotherCompanyCannotRerun() throws Exception {
        rerun(TestJwtSupport.userToken("other-user","DISPATCHER"),404);
        verifyNoInteractions(credits,engine,jobs,placements);
        verify(plans,never()).save(any());
    }
    @Test void owningDispatcherGetsExplicitUnavailableWithoutLosingCredits() throws Exception {
        rerun(TestJwtSupport.userToken("own-user","DISPATCHER"),503);
        verifyNoInteractions(entitlements);
        verifyNoInteractions(credits,engine,jobs,placements);
        verify(plans,never()).save(any());
    }
    @ParameterizedTest
    @ValueSource(strings={"DISPATCHER","MANAGER","ADMIN","COMPANY_ADMIN","WAREHOUSE_WORKER","DRIVER"})
    void legitimateCompanyReadersCanCompare(String role) throws Exception {
        mvc.perform(get("/api/load-plans/compare").param("planId1","47").param("planId2","47")
                .header("Authorization","Bearer "+TestJwtSupport.userToken("own-user",role))).andExpect(status().isOk());
        verifyNoInteractions(credits,engine);
    }
    @Test void comparisonCannotReadAnotherCompanyPlan() throws Exception {
        mvc.perform(get("/api/load-plans/compare").param("planId1","47").param("planId2","48")
                .header("Authorization","Bearer "+TestJwtSupport.userToken("own-user","MANAGER"))).andExpect(status().isNotFound());
        verifyNoInteractions(credits,engine);
    }
    @Test void anonymousComparisonIs401() throws Exception {
        mvc.perform(get("/api/load-plans/compare").param("planId1","47").param("planId2","47")).andExpect(status().isUnauthorized());
    }
    @Test void pinCannotModifyAnotherCompanyPlan() throws Exception {
        mvc.perform(post("/api/load-plans/47/pin").contentType("application/json").content("{\"placementId\":30}")
                .header("Authorization","Bearer "+TestJwtSupport.userToken("other-user","DISPATCHER"))).andExpect(status().isNotFound());
        verifyNoInteractions(placements,audit,credits,engine);
    }
    @Test void unpinCannotModifyAnotherCompanyPlan() throws Exception {
        mvc.perform(delete("/api/load-plans/47/pin/30")
                .header("Authorization","Bearer "+TestJwtSupport.userToken("other-user","DISPATCHER"))).andExpect(status().isNotFound());
        verifyNoInteractions(placements,audit,credits,engine);
    }
}
