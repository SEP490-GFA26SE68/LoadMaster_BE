package fu.se184491.loadmaster_be.service.subscription;

import fu.se184491.loadmaster_be.constant.billing.InvoiceStatus;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionStatus;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionTier;
import fu.se184491.loadmaster_be.dto.response.subscription.PlatformReportSummaryResponse;
import fu.se184491.loadmaster_be.entity.billing.CompanySubscription;
import fu.se184491.loadmaster_be.entity.billing.Invoice;
import fu.se184491.loadmaster_be.entity.billing.SubscriptionPlan;
import fu.se184491.loadmaster_be.repository.billing.CompanySubscriptionRepository;
import fu.se184491.loadmaster_be.repository.billing.InvoiceRepository;
import fu.se184491.loadmaster_be.repository.company.CompanyRepository;
import fu.se184491.loadmaster_be.service.subscription.Impl.PlatformReportServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlatformReportServiceTest {

    @Mock
    private CompanyRepository companyRepository;

    @Mock
    private CompanySubscriptionRepository companySubscriptionRepository;

    @Mock
    private InvoiceRepository invoiceRepository;

    @InjectMocks
    private PlatformReportServiceImpl platformReportService;

    @Test
    @DisplayName("getSummaryReport should calculate all platform metrics correctly")
    void getSummaryReport_success() {
        when(companyRepository.count()).thenReturn(15L);
        when(companySubscriptionRepository.countByStatus(SubscriptionStatus.ACTIVE)).thenReturn(10L);
        when(companySubscriptionRepository.countByStatusAndExpiresAtBetween(
                eq(SubscriptionStatus.ACTIVE), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(2L);

        Invoice paidInvoice1 = Invoice.builder()
                .totalAmount(BigDecimal.valueOf(10000000))
                .status(InvoiceStatus.PAID)
                .build();
        Invoice paidInvoice2 = Invoice.builder()
                .totalAmount(BigDecimal.valueOf(25000000))
                .status(InvoiceStatus.PAID)
                .build();

        when(invoiceRepository.findByStatus(InvoiceStatus.PAID)).thenReturn(List.of(paidInvoice1, paidInvoice2));

        SubscriptionPlan basicPlan = SubscriptionPlan.builder().tier(SubscriptionTier.BASIC).build();
        SubscriptionPlan proPlan = SubscriptionPlan.builder().tier(SubscriptionTier.PRO).build();
        SubscriptionPlan ultimatePlan = SubscriptionPlan.builder().tier(SubscriptionTier.ULTIMATE).build();

        CompanySubscription sub1 = CompanySubscription.builder().status(SubscriptionStatus.ACTIVE).plan(basicPlan).build();
        CompanySubscription sub2 = CompanySubscription.builder().status(SubscriptionStatus.ACTIVE).plan(proPlan).build();
        CompanySubscription sub3 = CompanySubscription.builder().status(SubscriptionStatus.ACTIVE).plan(proPlan).build();
        CompanySubscription sub4 = CompanySubscription.builder().status(SubscriptionStatus.ACTIVE).plan(ultimatePlan).build();
        CompanySubscription subExpired = CompanySubscription.builder().status(SubscriptionStatus.EXPIRED).plan(basicPlan).build();

        when(companySubscriptionRepository.findAll()).thenReturn(List.of(sub1, sub2, sub3, sub4, subExpired));

        PlatformReportSummaryResponse response = platformReportService.getSummaryReport();

        assertNotNull(response);
        assertEquals(15L, response.getTotalCompanies());
        assertEquals(10L, response.getActiveContracts());
        assertEquals(2L, response.getExpiringSoonContracts());
        assertEquals(BigDecimal.valueOf(35000000), response.getTotalRevenue());

        assertNotNull(response.getCompaniesByTier());
        assertEquals(1L, response.getCompaniesByTier().get(SubscriptionTier.BASIC));
        assertEquals(2L, response.getCompaniesByTier().get(SubscriptionTier.PRO));
        assertEquals(1L, response.getCompaniesByTier().get(SubscriptionTier.ULTIMATE));
    }
}
