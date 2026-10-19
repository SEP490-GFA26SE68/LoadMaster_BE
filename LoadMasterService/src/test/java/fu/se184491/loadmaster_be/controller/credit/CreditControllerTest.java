package fu.se184491.loadmaster_be.controller.credit;

import fu.se184491.loadmaster_be.constant.billing.PaymentGateway;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionStatus;
import fu.se184491.loadmaster_be.constant.billing.SubscriptionTier;
import fu.se184491.loadmaster_be.dto.ApiResponse;
import fu.se184491.loadmaster_be.dto.request.credit.CreditTopupRequest;
import fu.se184491.loadmaster_be.dto.response.credit.CreditBalanceResponse;
import fu.se184491.loadmaster_be.dto.response.payment.PaymentUrlResponse;
import fu.se184491.loadmaster_be.entity.billing.CompanySubscription;
import fu.se184491.loadmaster_be.entity.billing.SubscriptionPlan;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.billing.CompanySubscriptionRepository;
import fu.se184491.loadmaster_be.repository.company.CompanyRepository;
import fu.se184491.loadmaster_be.service.credit.CreditService;
import fu.se184491.loadmaster_be.service.payment.PaymentGatewayService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CreditControllerTest {

    @Mock
    private CreditService creditService;

    @Mock
    private PaymentGatewayService paymentGatewayService;

    @Mock
    private CompanyRepository companyRepository;

    @Mock
    private CompanySubscriptionRepository companySubscriptionRepository;

    @Mock
    private CurrentUserService currentUserService;

    @Mock
    private HttpServletRequest servletRequest;

    @InjectMocks
    private CreditController creditController;

    private Company company;

    @BeforeEach
    void setUp() {
        company = Company.builder().id(1L).companyName("Acme Logistics").build();
        lenient().when(currentUserService.getCurrentCompanyId()).thenReturn(1L);
        lenient().when(companyRepository.findById(1L)).thenReturn(Optional.of(company));
        lenient().when(servletRequest.getRemoteAddr()).thenReturn("127.0.0.1");
    }

    @Test
    @DisplayName("topup - BASIC plan charges 400 VND/credit and blocks when balance + credits > 3000")
    void topup_basicPlan_enforces3000CapAnd400VndPrice() {
        SubscriptionPlan basicPlan = SubscriptionPlan.builder()
                .id(1L)
                .name("Basic")
                .tier(SubscriptionTier.BASIC)
                .build();
        CompanySubscription sub = CompanySubscription.builder()
                .company(company)
                .plan(basicPlan)
                .status(SubscriptionStatus.ACTIVE)
                .build();
        when(companySubscriptionRepository.findByCompanyId(1L)).thenReturn(Optional.of(sub));
        when(creditService.getBalance(1L)).thenReturn(CreditBalanceResponse.builder()
                .companyId(1L)
                .balance(2500)
                .build());

        CreditTopupRequest overCapReq = CreditTopupRequest.builder()
                .credits(600)
                .gateway(PaymentGateway.VNPAY)
                .build();
        assertThrows(AppException.class, () -> creditController.topup(overCapReq, servletRequest));
        verify(paymentGatewayService, never()).createPayment(any(), any(), any(), any(), any(), any(), any());

        CreditTopupRequest validReq = CreditTopupRequest.builder()
                .credits(500)
                .gateway(PaymentGateway.VNPAY)
                .build();
        when(paymentGatewayService.createPayment(
                eq(company),
                eq(PaymentGateway.VNPAY),
                eq("CREDIT_TOPUP"),
                eq(500L),
                eq(new BigDecimal("200000")),
                anyString(),
                anyString()
        )).thenReturn(PaymentUrlResponse.builder().transactionId("TX_BASIC").status("PENDING").build());

        ResponseEntity<ApiResponse<PaymentUrlResponse>> res = creditController.topup(validReq, servletRequest);
        assertEquals("TX_BASIC", res.getBody().getData().getTransactionId());
    }

    @Test
    @DisplayName("topup - PRO plan charges 326 VND/credit and blocks when balance + credits > 10000")
    void topup_proPlan_enforces10000CapAnd326VndPrice() {
        SubscriptionPlan proPlan = SubscriptionPlan.builder()
                .id(2L)
                .name("Pro")
                .tier(SubscriptionTier.PRO)
                .build();
        CompanySubscription sub = CompanySubscription.builder()
                .company(company)
                .plan(proPlan)
                .status(SubscriptionStatus.ACTIVE)
                .build();
        when(companySubscriptionRepository.findByCompanyId(1L)).thenReturn(Optional.of(sub));
        when(creditService.getBalance(1L)).thenReturn(CreditBalanceResponse.builder()
                .companyId(1L)
                .balance(9500)
                .build());

        CreditTopupRequest overCapReq = CreditTopupRequest.builder()
                .credits(600)
                .gateway(PaymentGateway.VNPAY)
                .build();
        assertThrows(AppException.class, () -> creditController.topup(overCapReq, servletRequest));

        CreditTopupRequest validReq = CreditTopupRequest.builder()
                .credits(500)
                .gateway(PaymentGateway.VNPAY)
                .build();
        when(paymentGatewayService.createPayment(
                eq(company),
                eq(PaymentGateway.VNPAY),
                eq("CREDIT_TOPUP"),
                eq(500L),
                eq(new BigDecimal("163000")),
                anyString(),
                anyString()
        )).thenReturn(PaymentUrlResponse.builder().transactionId("TX_PRO").status("PENDING").build());

        ResponseEntity<ApiResponse<PaymentUrlResponse>> res = creditController.topup(validReq, servletRequest);
        assertEquals("TX_PRO", res.getBody().getData().getTransactionId());
    }

    @Test
    @DisplayName("topup - ULTIMATE plan charges 263 VND/credit with no cumulative cap")
    void topup_ultimatePlan_allowsUnlimitedCapAnd263VndPrice() {
        SubscriptionPlan ultimatePlan = SubscriptionPlan.builder()
                .id(3L)
                .name("Ultimate")
                .tier(SubscriptionTier.ULTIMATE)
                .build();
        CompanySubscription sub = CompanySubscription.builder()
                .company(company)
                .plan(ultimatePlan)
                .status(SubscriptionStatus.ACTIVE)
                .build();
        when(companySubscriptionRepository.findByCompanyId(1L)).thenReturn(Optional.of(sub));
        when(creditService.getBalance(1L)).thenReturn(CreditBalanceResponse.builder()
                .companyId(1L)
                .balance(30000)
                .build());

        CreditTopupRequest req = CreditTopupRequest.builder()
                .credits(5000)
                .gateway(PaymentGateway.VNPAY)
                .build();
        when(paymentGatewayService.createPayment(
                eq(company),
                eq(PaymentGateway.VNPAY),
                eq("CREDIT_TOPUP"),
                eq(5000L),
                eq(new BigDecimal("1315000")),
                anyString(),
                anyString()
        )).thenReturn(PaymentUrlResponse.builder().transactionId("TX_ULTIMATE").status("PENDING").build());

        ResponseEntity<ApiResponse<PaymentUrlResponse>> res = creditController.topup(req, servletRequest);
        assertEquals("TX_ULTIMATE", res.getBody().getData().getTransactionId());
    }

    @Test
    @DisplayName("getBalance - SYSTEM_SUPPORTER can query specific companyId while CompanyAdmin always uses own companyId")
    void getBalance_platformRoleCanQueryByCompanyId_whileCompanyUserAlwaysUsesOwnCompanyId() {
        when(currentUserService.hasRole("SYSTEM_SUPPORTER")).thenReturn(true);
        when(creditService.getBalance(7L)).thenReturn(CreditBalanceResponse.builder()
                .companyId(7L)
                .balance(4500)
                .build());

        ResponseEntity<ApiResponse<CreditBalanceResponse>> supportRes = creditController.getBalance(7L);
        assertEquals(7L, supportRes.getBody().getData().getCompanyId());
        assertEquals(4500, supportRes.getBody().getData().getBalance());

        when(currentUserService.hasRole("SYSTEM_SUPPORTER")).thenReturn(false);
        when(currentUserService.hasRole("SYSTEM_MANAGER")).thenReturn(false);
        when(currentUserService.hasRole("SYSTEM_ADMIN")).thenReturn(false);
        when(currentUserService.getCurrentCompanyId()).thenReturn(1L);
        when(creditService.getBalance(1L)).thenReturn(CreditBalanceResponse.builder()
                .companyId(1L)
                .balance(1200)
                .build());

        ResponseEntity<ApiResponse<CreditBalanceResponse>> adminRes = creditController.getBalance(7L);
        assertEquals(1L, adminRes.getBody().getData().getCompanyId());
        assertEquals(1200, adminRes.getBody().getData().getBalance());
    }
}

