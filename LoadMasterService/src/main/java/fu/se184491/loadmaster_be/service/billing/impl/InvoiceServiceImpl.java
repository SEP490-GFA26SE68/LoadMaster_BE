package fu.se184491.loadmaster_be.service.billing.impl;

import fu.se184491.loadmaster_be.constant.billing.InvoiceStatus;
import fu.se184491.loadmaster_be.constant.billing.PaymentStatus;
import fu.se184491.loadmaster_be.dto.response.billing.InvoiceResponse;
import fu.se184491.loadmaster_be.entity.billing.Invoice;
import fu.se184491.loadmaster_be.entity.billing.PaymentTransaction;
import fu.se184491.loadmaster_be.entity.billing.SubscriptionPlan;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.repository.billing.InvoiceRepository;
import fu.se184491.loadmaster_be.service.billing.InvoiceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class InvoiceServiceImpl implements InvoiceService {

    /** order_type của thanh toán nạp credit: targetId của giao dịch là số credit đã mua. */
    static final String CREDIT_TOPUP = "CREDIT_TOPUP";

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

    @Override
    @Transactional
    public Invoice recordPaymentInvoice(PaymentTransaction payment, SubscriptionPeriod period) {
        if (payment == null || payment.getId() == null || payment.getStatus() != PaymentStatus.SUCCESS) {
            throw new IllegalStateException("Chỉ ghi hoá đơn cho thanh toán đã lưu và đã thành công");
        }
        Optional<Invoice> existing = invoiceRepository.findByPaymentTransactionId(payment.getId());
        if (existing.isPresent()) {
            log.info("Payment {} already has invoice {}", payment.getGatewayTransactionId(), existing.get().getInvoiceNumber());
            return existing.get();
        }

        boolean creditTopup = CREDIT_TOPUP.equalsIgnoreCase(payment.getOrderType());
        // Như hoá đơn của hợp đồng (ContractServiceImpl): chưa tách thuế, tổng = tạm tính = số tiền đã trả
        BigDecimal amount = payment.getAmountVnd() != null ? payment.getAmountVnd() : BigDecimal.ZERO;
        SubscriptionPlan plan = period != null ? period.plan() : null;
        Invoice invoice = Invoice.builder()
                .company(payment.getCompany())
                .companySubscription(period != null ? period.subscription() : null)
                .paymentTransaction(payment)
                .plan(plan)
                .itemType(payment.getOrderType())
                .creditQuantity(creditTopup && payment.getTargetId() != null ? payment.getTargetId().intValue() : null)
                // Theo mã thanh toán chứ không theo giờ: hai thanh toán cùng mili giây không trùng số hoá đơn
                .invoiceNumber("INV-" + payment.getCompany().getId() + "-P" + payment.getId())
                .billingPeriod(billingPeriod(period))
                .subtotalAmount(amount)
                .taxAmount(BigDecimal.ZERO)
                .totalAmount(amount)
                .status(InvoiceStatus.PAID)
                .dueDate(LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS))
                .createdAt(Instant.now())
                .build();
        Invoice saved = invoiceRepository.save(invoice);
        log.info("Created invoice {} for payment {} ({})", saved.getInvoiceNumber(),
                payment.getGatewayTransactionId(), payment.getOrderType());
        return saved;
    }

    /** Kỳ dịch vụ dạng khoảng ngày ISO-8601 {@code 2026-11-13/2026-12-13}; nạp credit không có kỳ. */
    private static String billingPeriod(SubscriptionPeriod period) {
        if (period == null || period.start() == null || period.end() == null) {
            return null;
        }
        return period.start().toLocalDate() + "/" + period.end().toLocalDate();
    }

    private InvoiceResponse toResponse(Invoice invoice) {
        SubscriptionPlan plan = invoice.getPlan() != null ? invoice.getPlan()
                : (invoice.getCompanySubscription() != null ? invoice.getCompanySubscription().getPlan() : null);
        return InvoiceResponse.builder()
                .id(invoice.getId())
                .invoiceNumber(invoice.getInvoiceNumber())
                .companyId(invoice.getCompany() != null ? invoice.getCompany().getId() : null)
                .companyName(invoice.getCompany() != null ? invoice.getCompany().getCompanyName() : null)
                .companySubscriptionId(invoice.getCompanySubscription() != null ? invoice.getCompanySubscription().getId() : null)
                .planName(plan != null ? plan.getName() : null)
                .amount(invoice.getTotalAmount() != null ? invoice.getTotalAmount()
                        : (invoice.getSubtotalAmount() != null ? invoice.getSubtotalAmount() : BigDecimal.ZERO))
                .status(invoice.getStatus())
                .billingPeriod(invoice.getBillingPeriod())
                .createdAt(invoice.getCreatedAt())
                .paidAt(invoice.getDueDate())
                .itemType(invoice.getItemType())
                .creditQuantity(invoice.getCreditQuantity())
                .paymentReference(invoice.getPaymentTransaction() != null
                        ? invoice.getPaymentTransaction().getGatewayTransactionId() : null)
                .build();
    }
}
