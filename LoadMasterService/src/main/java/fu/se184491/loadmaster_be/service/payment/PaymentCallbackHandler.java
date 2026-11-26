package fu.se184491.loadmaster_be.service.payment;

import com.itextpdf.text.Document;
import com.itextpdf.text.Element;
import com.itextpdf.text.Font;
import com.itextpdf.text.FontFactory;
import com.itextpdf.text.Paragraph;
import com.itextpdf.text.pdf.PdfWriter;
import fu.se184491.loadmaster_be.client.BrevoClient;
import fu.se184491.loadmaster_be.client.VnPayClient;
import fu.se184491.loadmaster_be.constant.billing.BillingCycle;
import fu.se184491.loadmaster_be.constant.billing.PaymentStatus;
import fu.se184491.loadmaster_be.entity.billing.Invoice;
import fu.se184491.loadmaster_be.entity.billing.PaymentTransaction;
import fu.se184491.loadmaster_be.entity.billing.SubscriptionPlan;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.helpers.EmailTemplateBuilder;
import fu.se184491.loadmaster_be.helpers.LogMasks;
import fu.se184491.loadmaster_be.repository.billing.PaymentTransactionRepository;
import fu.se184491.loadmaster_be.service.billing.InvoiceService;
import fu.se184491.loadmaster_be.service.billing.InvoiceService.SubscriptionPeriod;
import fu.se184491.loadmaster_be.service.credit.CreditService;
import fu.se184491.loadmaster_be.service.subscription.SubscriptionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@Slf4j
public class PaymentCallbackHandler {

    private final PaymentTransactionRepository paymentTransactionRepository;
    private final VnPayClient vnPayClient;
    private final MockPaymentPolicy mockPolicy;
    private final fu.se184491.loadmaster_be.helpers.CurrentUserService currentUserService;
    private final SubscriptionService subscriptionService;
    private final CreditService creditService;
    private final InvoiceService invoiceService;
    private final fu.se184491.loadmaster_be.service.registration.GuestRegistrationService guestRegistrationService;
    private final BrevoClient brevoClient;

    @Autowired
    public PaymentCallbackHandler(
            PaymentTransactionRepository paymentTransactionRepository,
            VnPayClient vnPayClient,
            MockPaymentPolicy mockPolicy,
            fu.se184491.loadmaster_be.helpers.CurrentUserService currentUserService,
            SubscriptionService subscriptionService,
            CreditService creditService,
            InvoiceService invoiceService,
            fu.se184491.loadmaster_be.service.registration.GuestRegistrationService guestRegistrationService,
            @Autowired(required = false) BrevoClient brevoClient
    ) {
        this.paymentTransactionRepository = paymentTransactionRepository;
        this.vnPayClient = vnPayClient;
        this.mockPolicy = mockPolicy;
        this.currentUserService = currentUserService;
        this.subscriptionService = subscriptionService;
        this.creditService = creditService;
        this.invoiceService = invoiceService;
        this.guestRegistrationService = guestRegistrationService;
        this.brevoClient = brevoClient;
    }

    public PaymentCallbackHandler(
            PaymentTransactionRepository paymentTransactionRepository,
            VnPayClient vnPayClient,
            MockPaymentPolicy mockPolicy,
            fu.se184491.loadmaster_be.helpers.CurrentUserService currentUserService,
            SubscriptionService subscriptionService,
            CreditService creditService,
            InvoiceService invoiceService,
            fu.se184491.loadmaster_be.service.registration.GuestRegistrationService guestRegistrationService
    ) {
        this(paymentTransactionRepository, vnPayClient, mockPolicy, currentUserService, subscriptionService, creditService, invoiceService, guestRegistrationService, null);
    }

    @Transactional
    public Map<String, String> handleMockCheckout(String reference) {
        mockPolicy.requireEnabled();
        var txn = paymentTransactionRepository.findByGatewayTransactionIdForUpdate(reference)
                .orElseThrow(() -> new fu.se184491.loadmaster_be.exception.AppException(fu.se184491.loadmaster_be.exception.ErrorCode.RESOURCE_NOT_FOUND));
        if (txn.getCompany() == null || !java.util.Objects.equals(currentUserService.getCurrentCompanyId(), txn.getCompany().getId())) {
            throw new org.springframework.security.access.AccessDeniedException("Payment belongs to another company");
        }
        return handleVnPayCallback(Map.of("vnp_TxnRef", reference, "vnp_ResponseCode", "00"));
    }

    @Transactional
    public Map<String, String> handleVnPayCallback(Map<String, String> params) {
        Map<String, String> response = new HashMap<>();

        String txnRef = params.get("vnp_TxnRef");

        // 1. Verify Checksum — chỗ kiểm chữ ký duy nhất của mọi callback VNPay (cả đăng ký trả phí REG_…).
        // Chỉ bỏ qua khi máy chủ bật cấu hình vnpay.mock; tham số của request
        // (vnp_Mock, tiền tố MOCK_ của mã giao dịch) do người gọi tự đặt nên không bao giờ được tin.
        if (!mockPolicy.isEnabled()) {
            boolean validSignature = vnPayClient.verifyCallback(params);
            if (!validSignature) {
                log.warn("VNPay callback signature verification failed for params: {}", LogMasks.vnpayParams(params));
                response.put("RspCode", "97");
                response.put("Message", "Invalid Checksum");
                return response;
            }
        }

        if (txnRef != null && txnRef.startsWith("REG_")) {
            return guestRegistrationService.handlePaymentCallback(params);
        }

        // 2. Find Payment Transaction — khoá dòng tới hết giao dịch: IPN và trang trả về có thể tới cùng lúc,
        // lượt sau chờ lượt trước commit rồi thấy SUCCESS ở bước 3, không cấp credit / kích hoạt gói lần hai.
        Optional<PaymentTransaction> txnOpt = paymentTransactionRepository.findByGatewayTransactionIdForUpdate(txnRef);
        if (txnOpt.isEmpty()) {
            log.warn("VNPay callback order not found for txnRef: {}", txnRef);
            response.put("RspCode", "01");
            response.put("Message", "Order not Found");
            return response;
        }

        PaymentTransaction txn = txnOpt.get();

        // 3. Idempotency Check: Already success
        if (txn.getStatus() == PaymentStatus.SUCCESS) {
            log.info("VNPay callback already confirmed success for txnRef: {}", txnRef);
            response.put("RspCode", "00");
            response.put("Message", "Confirm Success");
            return response;
        }

        // 4. Verify Amount
        String vnpAmountStr = params.get("vnp_Amount");
        if (vnpAmountStr != null && txn.getAmountVnd() != null) {
            long vnpAmount = Long.parseLong(vnpAmountStr);
            long expectedAmount = txn.getAmountVnd().multiply(BigDecimal.valueOf(100)).longValue();
            if (vnpAmount != expectedAmount) {
                log.warn("VNPay amount mismatch for txnRef {}: expected {} but got {}", txnRef, expectedAmount, vnpAmount);
                response.put("RspCode", "04");
                response.put("Message", "Invalid Amount");
                return response;
            }
        }

        // 5. Process Payment Result
        String responseCode = params.get("vnp_ResponseCode");
        txn.setPayload(params.toString());

        if ("00".equals(responseCode)) {
            txn.setStatus(PaymentStatus.SUCCESS);
            txn.preUpdate();
            paymentTransactionRepository.save(txn);
            log.info("Payment SUCCESS for txnRef: {} (type: {}, targetId: {})",
                    txnRef, txn.getOrderType(), txn.getTargetId());

            SubscriptionPeriod period = null;
            if (("SUBSCRIPTION".equalsIgnoreCase(txn.getOrderType())
                    || "SUBSCRIPTION_RENEWAL".equalsIgnoreCase(txn.getOrderType())) && txn.getTargetId() != null) {
                period = subscriptionService.activateSubscription(txn.getCompany().getId(), txn.getTargetId());
            } else if ("SUBSCRIPTION_YEARLY".equalsIgnoreCase(txn.getOrderType()) && txn.getTargetId() != null) {
                period = subscriptionService.activateSubscription(
                        txn.getCompany().getId(),
                        txn.getTargetId(),
                        BillingCycle.YEARLY
                );
            } else if ("CREDIT_TOPUP".equalsIgnoreCase(txn.getOrderType()) && txn.getTargetId() != null) {
                creditService.addCredits(txn.getCompany().getId(), txn.getTargetId().intValue(), txn.getGatewayTransactionId());
            }
            // Cùng giao dịch với lần đánh dấu SUCCESS: callback gửi lại dừng ở bước 3, không có hoá đơn thứ hai
            Invoice savedInvoice = invoiceService.recordPaymentInvoice(txn, period);
            sendInvoiceEmail(savedInvoice, txn, period);
        } else {
            txn.setStatus(PaymentStatus.FAILED);
            txn.preUpdate();
            paymentTransactionRepository.save(txn);
            log.warn("Payment FAILED for txnRef: {} with vnp_ResponseCode: {}", txnRef, responseCode);
        }

        response.put("RspCode", "00");
        response.put("Message", "Confirm Success");
        return response;
    }

    private void sendInvoiceEmail(Invoice invoice, PaymentTransaction txn, SubscriptionPeriod period) {
        if (brevoClient == null || txn == null || txn.getCompany() == null || invoice == null) {
            return;
        }
        Company company = txn.getCompany();
        String recipientEmail = company.getBillingEmail();
        if (recipientEmail == null || recipientEmail.isBlank()) {
            return;
        }

        try {
            byte[] pdfBytes = generateInvoicePdf(company, txn, invoice, period);

            String serviceNameVi;
            String serviceNameEn;
            if ("CREDIT_TOPUP".equalsIgnoreCase(txn.getOrderType())) {
                serviceNameVi = "Nạp " + (txn.getTargetId() != null ? txn.getTargetId() : 0) + " Credit tối ưu hóa";
                serviceNameEn = "Top-up " + (txn.getTargetId() != null ? txn.getTargetId() : 0) + " Optimization Credits";
            } else {
                SubscriptionPlan plan = period != null ? period.plan() : null;
                String name = plan != null ? plan.getName() : "Gói dịch vụ LoadMaster";
                serviceNameVi = "Gói dịch vụ " + name;
                serviceNameEn = "Service Plan: " + name;
            }

            String amountFormatted = String.format("%,.0f đ", invoice.getTotalAmount() != null ? invoice.getTotalAmount() : BigDecimal.ZERO);

            List<EmailTemplateBuilder.AccountProvisionItem> items = List.of(
                    new EmailTemplateBuilder.AccountProvisionItem("Mã hóa đơn", "Invoice Number", invoice.getInvoiceNumber()),
                    new EmailTemplateBuilder.AccountProvisionItem("Mã giao dịch", "Transaction Code", txn.getGatewayTransactionId()),
                    new EmailTemplateBuilder.AccountProvisionItem("Dịch vụ", "Service", serviceNameVi + " / " + serviceNameEn),
                    new EmailTemplateBuilder.AccountProvisionItem("Số tiền thanh toán", "Amount Paid", amountFormatted),
                    new EmailTemplateBuilder.AccountProvisionItem("Trạng thái", "Status", "Đã thanh toán thành công (PAID) / Successfully Paid")
            );

            List<String> noticesVi = List.of(
                    "Hóa đơn điện tử chi tiết (file PDF) đã được đính kèm cùng email này.",
                    "Hệ thống đã tự động cập nhật gói cước và định mức credit cho doanh nghiệp.",
                    "Quý khách có thể xem lại lịch sử hóa đơn bất cứ lúc nào trong mục Hoá đơn trên hệ thống."
            );
            List<String> noticesEn = List.of(
                    "Detailed electronic invoice (PDF file) has been attached to this email.",
                    "Your subscription plan and credit quota have been automatically updated.",
                    "You can review your full invoice history anytime in the Invoices tab."
            );

            String html = EmailTemplateBuilder.buildAccountProvisionEmailHtml(
                    company.getCompanyName(),
                    "Hóa đơn Thanh toán Dịch vụ",
                    "Service Payment Invoice",
                    "Cảm ơn Quý khách đã thanh toán thành công dịch vụ trên nền tảng LoadMaster.",
                    "Thank you for your successful payment on LoadMaster Platform.",
                    items,
                    null,
                    noticesVi,
                    noticesEn
            );

            String subject = "[LoadMaster] Hóa đơn thanh toán dịch vụ / Service Payment Invoice - " + invoice.getInvoiceNumber();
            String attachmentName = invoice.getInvoiceNumber() + ".pdf";

            brevoClient.sendEmailWithAttachment(recipientEmail, subject, html, attachmentName, pdfBytes);
            log.info("Sent invoice email with PDF attachment to {} for invoice {}", LogMasks.email(recipientEmail), invoice.getInvoiceNumber());
        } catch (Exception e) {
            log.warn("Failed to send invoice email for invoice {}: {}", invoice.getInvoiceNumber(), e.getMessage());
        }
    }

    private byte[] generateInvoicePdf(
            Company company,
            PaymentTransaction txn,
            Invoice invoice,
            SubscriptionPeriod period
    ) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document document = new Document();
            PdfWriter.getInstance(document, out);
            document.open();

            Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18);
            Font boldFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12);
            Font normalFont = FontFactory.getFont(FontFactory.HELVETICA, 10);

            Paragraph title = new Paragraph("LOADMASTER - HOA DON THANH TOAN / INVOICE", titleFont);
            title.setAlignment(Element.ALIGN_CENTER);
            title.setSpacingAfter(20);
            document.add(title);

            document.add(new Paragraph("So hoa don / Invoice Number: " + invoice.getInvoiceNumber(), boldFont));
            document.add(new Paragraph("Ma giao dich / Transaction Ref: " + txn.getGatewayTransactionId(), normalFont));
            document.add(new Paragraph("Ngay / Date: " + LocalDate.now(), normalFont));
            document.add(new Paragraph("Cong ty / Company: " + (company != null ? company.getCompanyName() : "LoadMaster Customer"), normalFont));
            if (company != null && company.getTaxCode() != null && !company.getTaxCode().isBlank()) {
                document.add(new Paragraph("Ma so thue / Tax Code: " + company.getTaxCode(), normalFont));
            }
            if (company != null && company.getBillingEmail() != null && !company.getBillingEmail().isBlank()) {
                document.add(new Paragraph("Email: " + company.getBillingEmail(), normalFont));
            }
            document.add(new Paragraph("----------------------------------------------------------------------------------------------------", normalFont));

            String itemDescription;
            if ("CREDIT_TOPUP".equalsIgnoreCase(txn.getOrderType())) {
                itemDescription = "Nap Credit toi uu hoa (" + (txn.getTargetId() != null ? txn.getTargetId() : 0) + " credits)";
            } else {
                SubscriptionPlan plan = period != null ? period.plan() : null;
                itemDescription = "Goi dich vu: " + (plan != null ? plan.getName() : txn.getOrderType());
            }
            document.add(new Paragraph("Dich vu / Service: " + itemDescription, boldFont));
            if (invoice.getBillingPeriod() != null && !invoice.getBillingPeriod().isBlank()) {
                document.add(new Paragraph("Ky thanh toan / Billing Period: " + invoice.getBillingPeriod(), normalFont));
            }
            document.add(new Paragraph("Tong tien / Total Amount: " + String.format("%,.0f VND", invoice.getTotalAmount() != null ? invoice.getTotalAmount() : BigDecimal.ZERO), boldFont));
            document.add(new Paragraph("Trang thai / Status: DA THANH TOAN (PAID)", boldFont));

            document.add(new Paragraph("----------------------------------------------------------------------------------------------------", normalFont));
            Paragraph footer = new Paragraph("Cam on ban da su dung dich vu cua LoadMaster Platform!\nSupport: support@loadmaster.vn", normalFont);
            footer.setSpacingBefore(30);
            document.add(footer);

            document.close();
            return out.toByteArray();
        } catch (Exception e) {
            log.error("Failed to generate invoice PDF for invoice {}", invoice.getInvoiceNumber(), e);
            return new byte[0];
        }
    }
}