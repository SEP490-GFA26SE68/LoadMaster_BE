package fu.se184491.loadmaster_be.service.subscription.Impl;

import fu.se184491.loadmaster_be.constant.billing.InvoiceStatus;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionStatus;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionTier;
import fu.se184491.loadmaster_be.dto.response.subscription.PlatformReportSummaryResponse;
import fu.se184491.loadmaster_be.entity.billing.CompanySubscription;
import fu.se184491.loadmaster_be.repository.billing.CompanySubscriptionRepository;
import fu.se184491.loadmaster_be.repository.billing.InvoiceRepository;
import fu.se184491.loadmaster_be.repository.company.CompanyRepository;
import fu.se184491.loadmaster_be.service.subscription.PlatformReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PlatformReportServiceImpl implements PlatformReportService {

    private final CompanyRepository companyRepository;
    private final CompanySubscriptionRepository companySubscriptionRepository;
    private final InvoiceRepository invoiceRepository;

    @Override
    @Transactional(readOnly = true)
    public PlatformReportSummaryResponse getSummaryReport() {
        long totalCompanies = companyRepository.count();
        long activeContracts = companySubscriptionRepository.countByStatus(SubscriptionStatus.ACTIVE);

        LocalDateTime now = LocalDateTime.now();
        long expiringSoonContracts = companySubscriptionRepository.countByStatusAndExpiresAtBetween(
                SubscriptionStatus.ACTIVE, now, now.plusDays(30));

        BigDecimal totalRevenue = invoiceRepository.findByStatus(InvoiceStatus.PAID).stream()
                .map(inv -> inv.getTotalAmount() != null ? inv.getTotalAmount()
                        : (inv.getSubtotalAmount() != null ? inv.getSubtotalAmount() : BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<SubscriptionTier, Long> companiesByTier = new EnumMap<>(SubscriptionTier.class);
        for (SubscriptionTier tier : SubscriptionTier.values()) {
            companiesByTier.put(tier, 0L);
        }

        List<CompanySubscription> allSubscriptions = companySubscriptionRepository.findAll();
        for (CompanySubscription sub : allSubscriptions) {
            if (sub.getStatus() == SubscriptionStatus.ACTIVE && sub.getPlan() != null && sub.getPlan().getTier() != null) {
                companiesByTier.merge(sub.getPlan().getTier(), 1L, Long::sum);
            }
        }

        return PlatformReportSummaryResponse.builder()
                .totalCompanies(totalCompanies)
                .companiesByTier(companiesByTier)
                .activeContracts(activeContracts)
                .expiringSoonContracts(expiringSoonContracts)
                .totalRevenue(totalRevenue)
                .build();
    }
}
