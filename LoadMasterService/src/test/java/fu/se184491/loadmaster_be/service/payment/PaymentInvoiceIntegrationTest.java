package fu.se184491.loadmaster_be.service.payment;

import fu.se184491.loadmaster_be.client.BrevoClient;
import fu.se184491.loadmaster_be.client.KeycloakAdminClient;
import fu.se184491.loadmaster_be.constant.billing.BillingCycle;
import fu.se184491.loadmaster_be.constant.billing.InvoiceStatus;
import fu.se184491.loadmaster_be.constant.billing.PaymentGateway;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionStatus;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionTier;
import fu.se184491.loadmaster_be.constant.company.CompanyStatus;
import fu.se184491.loadmaster_be.constant.registration.RegistrationStatus;
import fu.se184491.loadmaster_be.entity.billing.CompanySubscription;
import fu.se184491.loadmaster_be.entity.billing.Invoice;
import fu.se184491.loadmaster_be.entity.billing.SubscriptionPlan;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.entity.registration.GuestRegistrationSession;
import fu.se184491.loadmaster_be.repository.billing.CompanySubscriptionRepository;
import fu.se184491.loadmaster_be.repository.billing.InvoiceRepository;
import fu.se184491.loadmaster_be.repository.company.CompanyRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Mỗi lối thanh toán thành công trên DB thật (H2) có đúng một hoá đơn trong danh sách của công ty: đăng ký gói, gói năm,
 * gia hạn, nạp credit, đăng ký trả phí của khách. Callback gửi lại (IPN rồi trang trả về, bấm xác nhận giả lập hai lần)
 * không tạo hoá đơn thứ hai; thanh toán thất bại hay bị huỷ không có hoá đơn.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:payment-invoice;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
        "spring.flyway.enabled=false",
        "spring.security.oauth2.client.registration.optimization-client.client-secret=test",
        "spring.security.oauth2.client.registration.keycloak-admin-client.client-secret=test",
        "app.brevo.api-key=test",
        "app.brevo.sender-email=test@example.com",
        "app.brevo.sender-name=Test",
        "app.brevo.security.otp-hmac-secret=test-secret",
        "vnpay.mock=true"
})
@Transactional
class PaymentInvoiceIntegrationTest {

    private static final String SUCCESS = "00";
    /** Khách huỷ giao dịch ở cổng thanh toán. */
    private static final String CANCELLED = "24";

    @Autowired private EntityManager entityManager;
    @Autowired private PaymentGatewayService paymentGatewayService;
    @Autowired private PaymentCallbackHandler paymentCallbackHandler;
    @Autowired private InvoiceRepository invoiceRepository;
    @Autowired private CompanySubscriptionRepository companySubscriptionRepository;
    @Autowired private CompanyRepository companyRepository;

    @MockitoBean private JwtDecoder jwtDecoder;
    @MockitoBean private KeycloakAdminClient keycloakAdminClient;
    @MockitoBean private BrevoClient brevoClient;

    private Company company;
    private SubscriptionPlan pro;

    @BeforeEach
    void setUp() {
        company = Company.builder()
                .companyCode("INV-CO")
                .companyName("Vận tải Hoá Đơn")
                .taxCode("0312345678")
                .billingEmail("ketoan@hoadon.test")
                .status(CompanyStatus.ACTIVE)
                .createdAt(Instant.now())
                .build();
        entityManager.persist(company);
        pro = SubscriptionPlan.builder()
                .planCode("INV-PRO")
                .name("Pro")
                .price(new BigDecimal("1490000"))
                .billingCycle(BillingCycle.MONTHLY)
                .tier(SubscriptionTier.PRO)
                .monthlyCredits(500)
                .active(true)
                .build();
        entityManager.persist(pro);
        entityManager.flush();
    }

    private String pay(String orderType, long targetId, String amount) {
        return paymentGatewayService.createPayment(company, PaymentGateway.MOCK, orderType, targetId,
                new BigDecimal(amount), "Thanh toán thử", "127.0.0.1").getTransactionId();
    }

    private void callback(String txnRef, String responseCode) {
        Map<String, String> result = paymentCallbackHandler.handleVnPayCallback(
                Map.of("vnp_TxnRef", txnRef, "vnp_ResponseCode", responseCode));
        assertThat(result.get("RspCode")).isEqualTo("00");
    }

    private List<Invoice> invoices(Long companyId) {
        entityManager.flush();
        return invoiceRepository.findByCompanyIdOrderByCreatedAtDesc(companyId);
    }

    @Test
    @DisplayName("Đăng ký gói: một hoá đơn PAID mang gói, số tiền và kỳ; gửi lại callback không thêm hoá đơn")
    void subscriptionPaymentCreatesOneInvoice() {
        String txnRef = pay("SUBSCRIPTION", pro.getId(), "1490000");

        callback(txnRef, SUCCESS);
        callback(txnRef, SUCCESS);

        List<Invoice> invoices = invoices(company.getId());
        assertThat(invoices).hasSize(1);
        Invoice invoice = invoices.get(0);
        CompanySubscription sub = companySubscriptionRepository.findByCompanyId(company.getId()).orElseThrow();
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.PAID);
        assertThat(invoice.getTotalAmount()).isEqualByComparingTo("1490000");
        assertThat(invoice.getPlan().getId()).isEqualTo(pro.getId());
        assertThat(invoice.getCompanySubscription().getId()).isEqualTo(sub.getId());
        assertThat(invoice.getItemType()).isEqualTo("SUBSCRIPTION");
        assertThat(invoice.getPaymentTransaction().getGatewayTransactionId()).isEqualTo(txnRef);
        assertThat(invoice.getBillingPeriod())
                .isEqualTo(sub.getStartedAt().toLocalDate() + "/" + sub.getExpiresAt().toLocalDate());
    }

    @Test
    @DisplayName("Gói năm: kỳ của hoá đơn dài 12 tháng")
    void yearlySubscriptionInvoiceCoversTwelveMonths() {
        String txnRef = pay("SUBSCRIPTION_YEARLY", pro.getId(), "14900000");

        callback(txnRef, SUCCESS);

        Invoice invoice = invoices(company.getId()).get(0);
        String[] period = invoice.getBillingPeriod().split("/");
        assertThat(java.time.LocalDate.parse(period[1]))
                .isEqualTo(java.time.LocalDate.parse(period[0]).plusMonths(12));
        assertThat(invoice.getTotalAmount()).isEqualByComparingTo("14900000");
    }

    @Test
    @DisplayName("Gia hạn gói còn hạn: hoá đơn riêng, kỳ nối tiếp hạn cũ")
    void renewalInvoiceStartsAtThePreviousExpiry() {
        LocalDateTime previousExpiry = LocalDateTime.now().plusDays(2).truncatedTo(ChronoUnit.SECONDS);
        entityManager.persist(CompanySubscription.builder()
                .company(company)
                .plan(pro)
                .status(SubscriptionStatus.ACTIVE)
                .startedAt(previousExpiry.minusMonths(1))
                .expiresAt(previousExpiry)
                .autoRenew(true)
                .build());
        entityManager.flush();
        String txnRef = pay("SUBSCRIPTION_RENEWAL", pro.getId(), "1490000");

        callback(txnRef, SUCCESS);
        callback(txnRef, SUCCESS);

        List<Invoice> invoices = invoices(company.getId());
        assertThat(invoices).hasSize(1);
        assertThat(invoices.get(0).getItemType()).isEqualTo("SUBSCRIPTION_RENEWAL");
        assertThat(invoices.get(0).getBillingPeriod())
                .isEqualTo(previousExpiry.toLocalDate() + "/" + previousExpiry.plusMonths(1).toLocalDate());
    }

    @Test
    @DisplayName("Nạp credit: hoá đơn ghi số credit và số tiền của thanh toán; xác nhận giả lập hai lần vẫn một hoá đơn")
    void creditTopupCreatesOneInvoice() {
        String txnRef = pay("CREDIT_TOPUP", 50, "50000");

        callback(txnRef, SUCCESS);
        callback(txnRef, SUCCESS);

        List<Invoice> invoices = invoices(company.getId());
        assertThat(invoices).hasSize(1);
        assertThat(invoices.get(0).getCreditQuantity()).isEqualTo(50);
        assertThat(invoices.get(0).getTotalAmount()).isEqualByComparingTo("50000");
        assertThat(invoices.get(0).getPlan()).isNull();
        assertThat(invoices.get(0).getBillingPeriod()).isNull();
    }

    @Test
    @DisplayName("Thanh toán bị huỷ hay thất bại: không có hoá đơn")
    void failedPaymentCreatesNoInvoice() {
        callback(pay("CREDIT_TOPUP", 50, "50000"), CANCELLED);
        callback(pay("SUBSCRIPTION", pro.getId(), "1490000"), "51");

        assertThat(invoices(company.getId())).isEmpty();
    }

    @Test
    @DisplayName("Mỗi thanh toán thành công một hoá đơn, số hoá đơn không trùng")
    void eachPaymentGetsItsOwnInvoice() {
        callback(pay("SUBSCRIPTION", pro.getId(), "1490000"), SUCCESS);
        callback(pay("CREDIT_TOPUP", 50, "50000"), SUCCESS);
        callback(pay("CREDIT_TOPUP", 500, "500000"), SUCCESS);

        List<Invoice> invoices = invoices(company.getId());
        assertThat(invoices).hasSize(3);
        assertThat(invoices).extracting(Invoice::getInvoiceNumber).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("Đăng ký trả phí của khách: công ty mới có một hoá đơn; callback gửi lại không thêm")
    void guestRegistrationPaymentCreatesOneInvoice() {
        when(keycloakAdminClient.createUser(anyString(), anyString())).thenReturn("kc-guest-invoice");
        GuestRegistrationSession session = GuestRegistrationSession.builder()
                .token("guest-invoice-token")
                .email("chu@khachmoi.test")
                .fullName("Nguyễn Khách Mới")
                .companyName("Khách Mới Logistics")
                .companyCode("KHACH-MOI")
                .taxCode("0398765432")
                .plan(pro)
                .billingCycle(BillingCycle.MONTHLY)
                .status(RegistrationStatus.PAYMENT_PENDING)
                .paymentRef("REG_INVOICE01")
                .paymentGateway(PaymentGateway.VNPAY)
                .amountVnd(new BigDecimal("1490000"))
                .createdAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusHours(1))
                .build();
        entityManager.persist(session);
        entityManager.flush();

        callback("REG_INVOICE01", SUCCESS);
        callback("REG_INVOICE01", SUCCESS);

        Company created = companyRepository.findAll().stream()
                .filter(c -> "KHACH-MOI".equals(c.getCompanyCode()))
                .findFirst().orElseThrow();
        List<Invoice> invoices = invoices(created.getId());
        assertThat(invoices).hasSize(1);
        Invoice invoice = invoices.get(0);
        assertThat(invoice.getItemType()).isEqualTo("GUEST_REGISTRATION");
        assertThat(invoice.getTotalAmount()).isEqualByComparingTo("1490000");
        assertThat(invoice.getPlan().getId()).isEqualTo(pro.getId());
        assertThat(invoice.getPaymentTransaction().getGatewayTransactionId()).isEqualTo("REG_INVOICE01");
        assertThat(invoice.getBillingPeriod()).isNotNull();
    }
}
