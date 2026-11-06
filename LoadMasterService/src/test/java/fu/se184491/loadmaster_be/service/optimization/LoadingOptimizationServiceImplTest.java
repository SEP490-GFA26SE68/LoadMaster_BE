package fu.se184491.loadmaster_be.service.optimization;

import fu.se184491.loadmaster_be.client.OptimizationClient;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionStatus;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionTier;
import fu.se184491.loadmaster_be.dto.response.credit.CreditBalanceResponse;
import fu.se184491.loadmaster_be.dto.response.optimization.OptimizationJobStartResponse;
import fu.se184491.loadmaster_be.entity.billing.CompanySubscription;
import fu.se184491.loadmaster_be.entity.billing.SubscriptionPlan;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.billing.CompanySubscriptionRepository;
import fu.se184491.loadmaster_be.repository.trip.TripRepository;
import fu.se184491.loadmaster_be.service.credit.CreditService;
import fu.se184491.loadmaster_be.service.optimization.Impl.LoadingOptimizationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LoadingOptimizationServiceImplTest {

    private static final long COMPANY = 1L;

    @Mock TripRepository tripRepository;
    @Mock CurrentUserService currentUserService;
    @Mock CompanySubscriptionRepository companySubscriptionRepository;
    @Mock CreditService creditService;
    @Mock OptimizationClient optimizationClient;
    @Mock LoadPlanQueryService loadPlanQueryService;

    @InjectMocks LoadingOptimizationServiceImpl service;

    @BeforeEach
    void ownTrip() {
        when(currentUserService.getCurrentCompanyId()).thenReturn(COMPANY);
        when(tripRepository.findByIdAndCompanyId(10L, COMPANY)).thenReturn(Optional.of(new Trip()));
        when(optimizationClient.createJob(any())).thenReturn("{}");
        when(optimizationClient.jobIdOf("{}")).thenReturn("job-1");
    }

    private void subscription(SubscriptionTier tier, SubscriptionStatus status) {
        SubscriptionPlan plan = SubscriptionPlan.builder().tier(tier).build();
        CompanySubscription sub = CompanySubscription.builder().plan(plan).status(status).build();
        when(companySubscriptionRepository.findByCompanyId(COMPANY)).thenReturn(Optional.of(sub));
    }

    private void balance(int credits) {
        when(creditService.getBalance(COMPANY))
                .thenReturn(CreditBalanceResponse.builder().balance(credits).build());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sentRequest() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(optimizationClient).createJob(captor.capture());
        return (Map<String, Object>) captor.getValue();
    }

    private static ErrorCode codeOf(Throwable error) {
        return ((AppException) error).getErrorCode();
    }

    @Test
    void sendsCompanyAndTierSoTheServiceDeductsCredit() {
        subscription(SubscriptionTier.PRO, SubscriptionStatus.ACTIVE);
        balance(5);

        OptimizationJobStartResponse result = service.startLoadingOptimization(10L);

        assertThat(sentRequest())
                .containsEntry("trip_id", 10L)
                .containsEntry("company_id", "1")
                .containsEntry("subscription_tier", "PRO");
        assertThat(result.getJobId()).isEqualTo("job-1");
        assertThat(result.getSubscriptionTier()).isEqualTo("PRO");
    }

    @Test
    void noCreditLeftIsRejectedBeforeCreatingAJob() {
        subscription(SubscriptionTier.BASIC, SubscriptionStatus.ACTIVE);
        balance(0);

        assertThatThrownBy(() -> service.startLoadingOptimization(10L))
                .isInstanceOf(AppException.class)
                .extracting(LoadingOptimizationServiceImplTest::codeOf)
                .isEqualTo(ErrorCode.INSUFFICIENT_CREDITS);
        verify(optimizationClient, never()).createJob(any());
    }

    @Test
    void ultimateSkipsTheBalanceCheck() {
        subscription(SubscriptionTier.ULTIMATE, SubscriptionStatus.ACTIVE);
        balance(0);

        service.startLoadingOptimization(10L);

        assertThat(sentRequest()).containsEntry("subscription_tier", "ULTIMATE");
        verify(creditService, never()).getBalance(any());
    }

    @Test
    void expiredSubscriptionRunsAsBasic() {
        subscription(SubscriptionTier.PRO, SubscriptionStatus.EXPIRED);
        balance(3);

        service.startLoadingOptimization(10L);

        assertThat(sentRequest()).containsEntry("subscription_tier", "BASIC");
    }

    @Test
    void tripOfAnotherCompanyIsNotFound() {
        when(tripRepository.findByIdAndCompanyId(99L, COMPANY)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.startLoadingOptimization(99L))
                .isInstanceOf(AppException.class)
                .extracting(LoadingOptimizationServiceImplTest::codeOf)
                .isEqualTo(ErrorCode.TRIP_NOT_FOUND);
    }
}
