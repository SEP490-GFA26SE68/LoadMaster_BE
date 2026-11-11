package fu.se184491.loadmaster_be.controller.credit;

import fu.se184491.loadmaster_be.constant.billing.PaymentGateway;
import fu.se184491.loadmaster_be.dto.ApiResponse;
import fu.se184491.loadmaster_be.dto.request.credit.CreditTopupRequest;
import fu.se184491.loadmaster_be.dto.response.credit.CreditBalanceResponse;
import fu.se184491.loadmaster_be.dto.response.payment.PaymentUrlResponse;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.service.credit.CreditService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CreditControllerTest {

    @Mock
    private CreditService creditService;

    @Mock
    private CurrentUserService currentUserService;

    @Mock
    private HttpServletRequest servletRequest;

    @InjectMocks
    private CreditController creditController;

    @BeforeEach
    void setUp() {
        lenient().when(currentUserService.getCurrentCompanyId()).thenReturn(1L);
        lenient().when(servletRequest.getRemoteAddr()).thenReturn("127.0.0.1");
    }

    @Test
    @DisplayName("topup - delegates to CreditService.topup with current companyId and client IP")
    void topup_delegatesToCreditService() {
        CreditTopupRequest req = CreditTopupRequest.builder()
                .credits(1500)
                .gateway(PaymentGateway.VNPAY)
                .build();
        when(creditService.topup(1L, req, "127.0.0.1"))
                .thenReturn(PaymentUrlResponse.builder().transactionId("TX_BASIC").status("PENDING").build());

        ResponseEntity<ApiResponse<PaymentUrlResponse>> res = creditController.topup(req, servletRequest);
        assertEquals("TX_BASIC", res.getBody().getData().getTransactionId());
        verify(creditService).topup(1L, req, "127.0.0.1");
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

