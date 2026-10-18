package fu.se184491.loadmaster_be.service.billing.impl;

import fu.se184491.loadmaster_be.constant.billing.InvoiceStatus;
import fu.se184491.loadmaster_be.dto.response.billing.InvoiceResponse;
import fu.se184491.loadmaster_be.entity.billing.Invoice;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.billing.InvoiceRepository;
import fu.se184491.loadmaster_be.service.billing.InvoiceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
public class InvoiceServiceImpl implements InvoiceService {

    private final InvoiceRepository invoiceRepository;

    @Override
    @Transactional(readOnly = true)
    public List<InvoiceResponse> getCompanyInvoices(Long companyId, InvoiceStatus status) {
        List<Invoice> invoices;
        if (companyId != null) {
            if (status != null) {
                invoices = invoiceRepository.findByCompanyIdAndStatusOrderByCreatedAtDesc(companyId, status);
            } else {
                invoices = invoiceRepository.findByCompanyIdOrderByCreatedAtDesc(companyId);
            }
        } else {
            if (status != null) {
                invoices = invoiceRepository.findByStatus(status);
            } else {
                invoices = invoiceRepository.findAll();
            }
        }

        return invoices.stream().map(this::toResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public InvoiceResponse getInvoiceById(Long invoiceId, Long callerCompanyId, boolean isPlatformAdmin) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new AppException(ErrorCode.INVOICE_NOT_FOUND));

        if (!isPlatformAdmin) {
            if (callerCompanyId == null || invoice.getCompany() == null || !callerCompanyId.equals(invoice.getCompany().getId())) {
                throw new AppException(ErrorCode.UNAUTHORIZED);
            }
        }

        return toResponse(invoice);
    }

    private InvoiceResponse toResponse(Invoice invoice) {
        return InvoiceResponse.builder()
                .id(invoice.getId())
                .invoiceNumber(invoice.getInvoiceNumber())
                .companyId(invoice.getCompany() != null ? invoice.getCompany().getId() : null)
                .companyName(invoice.getCompany() != null ? invoice.getCompany().getCompanyName() : null)
                .companySubscriptionId(invoice.getCompanySubscription() != null ? invoice.getCompanySubscription().getId() : null)
                .planName(invoice.getCompanySubscription() != null && invoice.getCompanySubscription().getPlan() != null
                        ? invoice.getCompanySubscription().getPlan().getName() : null)
                .amount(invoice.getTotalAmount() != null ? invoice.getTotalAmount()
                        : (invoice.getSubtotalAmount() != null ? invoice.getSubtotalAmount() : BigDecimal.ZERO))
                .status(invoice.getStatus())
                .billingPeriod(invoice.getBillingPeriod())
                .createdAt(invoice.getCreatedAt())
                .paidAt(invoice.getDueDate())
                .build();
    }
}
