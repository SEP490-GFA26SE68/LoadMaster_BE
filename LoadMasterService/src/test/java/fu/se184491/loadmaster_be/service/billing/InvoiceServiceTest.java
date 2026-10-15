package fu.se184491.loadmaster_be.service.billing;

import fu.se184491.loadmaster_be.constant.billing.InvoiceStatus;
import fu.se184491.loadmaster_be.dto.response.billing.InvoiceResponse;
import fu.se184491.loadmaster_be.entity.billing.CompanySubscription;
import fu.se184491.loadmaster_be.entity.billing.Invoice;
import fu.se184491.loadmaster_be.entity.billing.SubscriptionPlan;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.billing.InvoiceRepository;
import fu.se184491.loadmaster_be.service.billing.impl.InvoiceServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InvoiceServiceTest {

    @Mock
    private InvoiceRepository invoiceRepository;

    @InjectMocks
    private InvoiceServiceImpl invoiceService;

    private Company company;
    private SubscriptionPlan plan;
    private CompanySubscription subscription;
    private Invoice invoice;

    @BeforeEach
    void setUp() {
        company = Company.builder()
                .id(10L)
                .companyCode("COMP-10")
                .companyName("Công ty An Phát")
                .build();

        plan = SubscriptionPlan.builder()
                .id(1L)
                .name("Gói Pro")
                .build();

        subscription = CompanySubscription.builder()
                .id(5L)
                .company(company)
                .plan(plan)
                .build();

        invoice = Invoice.builder()
                .id(1L)
                .invoiceNumber("INV-001")
                .company(company)
                .companySubscription(subscription)
                .billingPeriod("2026-10")
                .totalAmount(BigDecimal.valueOf(5000000))
                .status(InvoiceStatus.PAID)
                .createdAt(Instant.now())
                .dueDate(LocalDateTime.now())
                .build();
    }

    @Test
    @DisplayName("getCompanyInvoices without status should return all company invoices")
    void getCompanyInvoices_withoutStatus() {
        when(invoiceRepository.findByCompanyIdOrderByCreatedAtDesc(10L)).thenReturn(List.of(invoice));

        List<InvoiceResponse> result = invoiceService.getCompanyInvoices(10L, null);

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("INV-001", result.get(0).getInvoiceNumber());
        assertEquals("Gói Pro", result.get(0).getPlanName());
        verify(invoiceRepository).findByCompanyIdOrderByCreatedAtDesc(10L);
    }

    @Test
    @DisplayName("getCompanyInvoices with status should filter by status")
    void getCompanyInvoices_withStatus() {
        when(invoiceRepository.findByCompanyIdAndStatusOrderByCreatedAtDesc(10L, InvoiceStatus.PAID))
                .thenReturn(List.of(invoice));

        List<InvoiceResponse> result = invoiceService.getCompanyInvoices(10L, InvoiceStatus.PAID);

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(InvoiceStatus.PAID, result.get(0).getStatus());
        verify(invoiceRepository).findByCompanyIdAndStatusOrderByCreatedAtDesc(10L, InvoiceStatus.PAID);
    }

    @Test
    @DisplayName("getInvoiceById should return invoice for owner company")
    void getInvoiceById_ownerCompany_success() {
        when(invoiceRepository.findById(1L)).thenReturn(Optional.of(invoice));

        InvoiceResponse response = invoiceService.getInvoiceById(1L, 10L, false);

        assertNotNull(response);
        assertEquals(1L, response.getId());
        assertEquals("INV-001", response.getInvoiceNumber());
    }

    @Test
    @DisplayName("getInvoiceById should return invoice for platform admin regardless of company")
    void getInvoiceById_platformAdmin_success() {
        when(invoiceRepository.findById(1L)).thenReturn(Optional.of(invoice));

        InvoiceResponse response = invoiceService.getInvoiceById(1L, 999L, true);

        assertNotNull(response);
        assertEquals(1L, response.getId());
    }

    @Test
    @DisplayName("getInvoiceById should throw UNAUTHORIZED when caller company differs")
    void getInvoiceById_unauthorized() {
        when(invoiceRepository.findById(1L)).thenReturn(Optional.of(invoice));

        AppException ex = assertThrows(AppException.class, () ->
                invoiceService.getInvoiceById(1L, 999L, false));

        assertEquals(ErrorCode.UNAUTHORIZED, ex.getErrorCode());
    }

    @Test
    @DisplayName("getInvoiceById should throw INVOICE_NOT_FOUND when not found")
    void getInvoiceById_notFound() {
        when(invoiceRepository.findById(999L)).thenReturn(Optional.empty());

        AppException ex = assertThrows(AppException.class, () ->
                invoiceService.getInvoiceById(999L, 10L, false));

        assertEquals(ErrorCode.INVOICE_NOT_FOUND, ex.getErrorCode());
    }
}
