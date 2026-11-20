package fu.se184491.loadmaster_be.service.payment;

import fu.se184491.loadmaster_be.config.payment.VnPayConfig;
import fu.se184491.loadmaster_be.client.VnPayClient;
import fu.se184491.loadmaster_be.entity.billing.PaymentTransaction;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.constant.billing.PaymentGateway;
import fu.se184491.loadmaster_be.constant.billing.PaymentStatus;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.billing.PaymentTransactionRepository;
import fu.se184491.loadmaster_be.service.credit.CreditService;
import fu.se184491.loadmaster_be.service.subscription.SubscriptionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.access.AccessDeniedException;
import java.math.BigDecimal;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MockPaymentPolicyTest {
    private MockPaymentPolicy policy(boolean flag,String... profiles) {
        var config=new VnPayConfig();config.setMock(flag);
        var env=new MockEnvironment();env.setActiveProfiles(profiles);
        return new MockPaymentPolicy(config,env);
    }
    @ParameterizedTest @ValueSource(strings={"prod","production","default","dev,prod","dev,production"})
    void nonDevelopmentOrProductionProfilesAlwaysDisableMock(String profiles) {
        var policy=policy(true,profiles.split(","));
        assertFalse(policy.isEnabled());assertThrows(AccessDeniedException.class,policy::requireEnabled);
    }
    @Test void flagWithoutProfileDoesNotEnableMock() {
        assertFalse(policy(true).isEnabled());
    }
    @Test void developmentRequiresExplicitFlag() {
        assertFalse(policy(false,"dev").isEnabled());
        assertTrue(policy(true,"dev").isEnabled());
    }
    @Test void productionCannotCreatePendingMockPayment() {
        var repo=mock(PaymentTransactionRepository.class);
        var service=new PaymentGatewayService(repo,mock(VnPayClient.class),policy(true,"prod"));
        assertThrows(AccessDeniedException.class,()->service.createPayment(Company.builder().id(1L).build(),
                PaymentGateway.MOCK,"CREDIT_TOPUP",10L,BigDecimal.TEN,"test","127.0.0.1"));
        verifyNoInteractions(repo);
    }
    @Test void devMockCheckoutCannotPayAnotherCompanyTransaction() {
        var repo=mock(PaymentTransactionRepository.class);
        var current=mock(CurrentUserService.class);
        var credits=mock(CreditService.class);
        var subscriptions=mock(SubscriptionService.class);
        var transaction=PaymentTransaction.builder().company(Company.builder().id(1L).build()).build();
        when(repo.findByGatewayTransactionIdForUpdate("REF")).thenReturn(Optional.of(transaction));
        when(current.getCurrentCompanyId()).thenReturn(2L);
        var handler=new PaymentCallbackHandler(repo,mock(VnPayClient.class),policy(true,"dev"),current,subscriptions,credits,mock(fu.se184491.loadmaster_be.service.billing.InvoiceService.class),mock(fu.se184491.loadmaster_be.service.registration.GuestRegistrationService.class));
        assertThrows(AccessDeniedException.class,()->handler.handleMockCheckout("REF"));
        verify(repo,never()).save(any());verifyNoInteractions(credits,subscriptions);
    }
    @Test void enabledDevMockCheckoutWorksForOwnerOnly() {
        var repo=mock(PaymentTransactionRepository.class);
        var current=mock(CurrentUserService.class);
        var credits=mock(CreditService.class);
        var subscriptions=mock(SubscriptionService.class);
        var transaction=PaymentTransaction.builder().company(Company.builder().id(1L).build())
                .status(PaymentStatus.PENDING).orderType("CREDIT_TOPUP").targetId(10L)
                .gatewayTransactionId("REF").build();
        when(repo.findByGatewayTransactionIdForUpdate("REF")).thenReturn(Optional.of(transaction));
        when(current.getCurrentCompanyId()).thenReturn(1L);
        var handler=new PaymentCallbackHandler(repo,mock(VnPayClient.class),policy(true,"dev"),current,subscriptions,credits,mock(fu.se184491.loadmaster_be.service.billing.InvoiceService.class),mock(fu.se184491.loadmaster_be.service.registration.GuestRegistrationService.class));
        assertEquals("00",handler.handleMockCheckout("REF").get("RspCode"));
        verify(credits).addCredits(1L,10,"REF");
    }
}
