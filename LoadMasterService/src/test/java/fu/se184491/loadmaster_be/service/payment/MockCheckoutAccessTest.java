package fu.se184491.loadmaster_be.service.payment;

import fu.se184491.loadmaster_be.entity.billing.PaymentTransaction;
import fu.se184491.loadmaster_be.entity.company.Company;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.billing.PaymentTransactionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MockCheckoutAccessTest {

    @Mock PaymentTransactionRepository paymentTransactionRepository;
    @Mock CurrentUserService currentUserService;

    @InjectMocks MockCheckoutAccess access;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void signIn() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("kc-1").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    private static PaymentTransaction transactionOf(long companyId) {
        Company company = new Company();
        company.setId(companyId);
        PaymentTransaction transaction = new PaymentTransaction();
        transaction.setCompany(company);
        return transaction;
    }

    private static ErrorCode codeOf(Throwable error) {
        return ((AppException) error).getErrorCode();
    }

    @Test
    void anonymousCallerIsRejected() {
        assertThatThrownBy(() -> access.requireAllowed("MOCK_1"))
                .isInstanceOf(AppException.class)
                .extracting(MockCheckoutAccessTest::codeOf)
                .isEqualTo(ErrorCode.UNAUTHENTICATED);
        verify(paymentTransactionRepository, never()).findByGatewayTransactionId(anyString());
    }

    @Test
    void ownCompanyTransactionIsAllowed() {
        signIn();
        when(currentUserService.getCurrentCompanyId()).thenReturn(5L);
        when(paymentTransactionRepository.findByGatewayTransactionId("MOCK_1")).thenReturn(Optional.of(transactionOf(5L)));

        assertThatCode(() -> access.requireAllowed("MOCK_1")).doesNotThrowAnyException();
    }

    @Test
    void transactionOfAnotherCompanyLooksMissing() {
        signIn();
        when(currentUserService.getCurrentCompanyId()).thenReturn(5L);
        when(paymentTransactionRepository.findByGatewayTransactionId("MOCK_1")).thenReturn(Optional.of(transactionOf(6L)));

        assertThatThrownBy(() -> access.requireAllowed("MOCK_1"))
                .extracting(MockCheckoutAccessTest::codeOf)
                .isEqualTo(ErrorCode.PAYMENT_TRANSACTION_NOT_FOUND);
    }

    @Test
    void unknownTransactionIsNotFound() {
        signIn();
        when(currentUserService.getCurrentCompanyId()).thenReturn(5L);
        when(paymentTransactionRepository.findByGatewayTransactionId("MOCK_X")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> access.requireAllowed("MOCK_X"))
                .extracting(MockCheckoutAccessTest::codeOf)
                .isEqualTo(ErrorCode.PAYMENT_TRANSACTION_NOT_FOUND);
    }

    @Test
    void guestRegistrationPaymentNeedsNoLogin() {
        assertThatCode(() -> access.requireAllowed("REG_ABC12345")).doesNotThrowAnyException();
        verify(paymentTransactionRepository, never()).findByGatewayTransactionId(anyString());
    }
}
