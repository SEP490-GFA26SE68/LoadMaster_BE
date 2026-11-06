package fu.se184491.loadmaster_be.service.registration;

import fu.se184491.loadmaster_be.dto.request.registration.AcceptPolicyRequest;
import fu.se184491.loadmaster_be.dto.request.registration.GuestRegistrationInitRequest;
import fu.se184491.loadmaster_be.dto.request.registration.InitPaymentRequest;
import fu.se184491.loadmaster_be.dto.request.registration.VerifyOtpRequest;
import fu.se184491.loadmaster_be.dto.response.registration.GuestRegistrationSessionResponse;
import fu.se184491.loadmaster_be.dto.response.registration.PaymentLinkResponse;
import fu.se184491.loadmaster_be.dto.response.registration.ResendOtpResponse;

import java.util.Map;

public interface GuestRegistrationService {

    GuestRegistrationSessionResponse initRegistration(GuestRegistrationInitRequest request);

    GuestRegistrationSessionResponse verifyOtp(VerifyOtpRequest request);

    ResendOtpResponse resendOtp(String token);

    GuestRegistrationSessionResponse acceptPolicy(AcceptPolicyRequest request, String ipAddress);

    PaymentLinkResponse initPayment(InitPaymentRequest request, String ipAddress);

    Map<String, String> handlePaymentCallback(Map<String, String> params);

    GuestRegistrationSessionResponse getSessionStatus(String token);
}
