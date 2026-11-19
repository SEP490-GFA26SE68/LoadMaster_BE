package fu.se184491.loadmaster_be.service.billing;

import fu.se184491.loadmaster_be.constant.billing.InvoiceStatus;
import fu.se184491.loadmaster_be.dto.response.billing.InvoiceResponse;
import fu.se184491.loadmaster_be.entity.billing.CompanySubscription;
import fu.se184491.loadmaster_be.entity.billing.Invoice;
import fu.se184491.loadmaster_be.entity.billing.PaymentTransaction;
import fu.se184491.loadmaster_be.entity.billing.SubscriptionPlan;

import java.time.LocalDateTime;
import java.util.List;

public interface InvoiceService {

    List<InvoiceResponse> getCompanyInvoices(Long companyId, InvoiceStatus status);

    InvoiceResponse getInvoiceById(Long invoiceId, Long callerCompanyId, boolean isPlatformAdmin);

    /**
     * Hoá đơn PAID cho một thanh toán thành công (gói, gia hạn, nạp credit, đăng ký trả phí). Gọi trong cùng giao dịch
     * với lúc đánh dấu thanh toán SUCCESS. Thanh toán đã có hoá đơn thì trả lại hoá đơn đó, không tạo thêm.
     *
     * @param payment thanh toán đã lưu, trạng thái SUCCESS
     * @param period  kỳ dịch vụ thanh toán đã mua; {@code null} với nạp credit
     */
    Invoice recordPaymentInvoice(PaymentTransaction payment, SubscriptionPeriod period);

    /** Kỳ dịch vụ một thanh toán gói đã mua: gói, bản ghi gói của công ty, từ lúc nào tới lúc nào. */
    record SubscriptionPeriod(CompanySubscription subscription, SubscriptionPlan plan,
                              LocalDateTime start, LocalDateTime end) {
    }
}
