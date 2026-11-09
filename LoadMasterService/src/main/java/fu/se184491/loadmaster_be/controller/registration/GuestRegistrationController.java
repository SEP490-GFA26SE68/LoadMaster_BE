package fu.se184491.loadmaster_be.controller.registration;

import fu.se184491.loadmaster_be.dto.request.registration.AcceptPolicyRequest;
import fu.se184491.loadmaster_be.dto.request.registration.GuestRegistrationInitRequest;
import fu.se184491.loadmaster_be.dto.request.registration.InitPaymentRequest;
import fu.se184491.loadmaster_be.dto.request.registration.VerifyOtpRequest;
import fu.se184491.loadmaster_be.dto.response.registration.GuestRegistrationSessionResponse;
import fu.se184491.loadmaster_be.dto.response.registration.PaymentLinkResponse;
import fu.se184491.loadmaster_be.dto.response.registration.ResendOtpResponse;
import fu.se184491.loadmaster_be.service.registration.GuestRegistrationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/guest-registration")
@RequiredArgsConstructor
public class GuestRegistrationController {

    private final GuestRegistrationService guestRegistrationService;

    @PostMapping("/init")
    public ResponseEntity<GuestRegistrationSessionResponse> initRegistration(
            @Valid @RequestBody GuestRegistrationInitRequest request
    ) {
        GuestRegistrationSessionResponse response = guestRegistrationService.initRegistration(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/verify-otp")
    public ResponseEntity<GuestRegistrationSessionResponse> verifyOtp(
            @Valid @RequestBody VerifyOtpRequest request
    ) {
        GuestRegistrationSessionResponse response = guestRegistrationService.verifyOtp(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/resend-otp")
    public ResponseEntity<ResendOtpResponse> resendOtp(
            @RequestParam String token
    ) {
        ResendOtpResponse response = guestRegistrationService.resendOtp(token);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/accept-policy")
    public ResponseEntity<GuestRegistrationSessionResponse> acceptPolicy(
            @Valid @RequestBody AcceptPolicyRequest request,
            HttpServletRequest servletRequest
    ) {
        String ipAddress = servletRequest.getRemoteAddr();
        GuestRegistrationSessionResponse response = guestRegistrationService.acceptPolicy(request, ipAddress);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/init-payment")
    public ResponseEntity<PaymentLinkResponse> initPayment(
            @Valid @RequestBody InitPaymentRequest request,
            HttpServletRequest servletRequest
    ) {
        String ipAddress = servletRequest.getRemoteAddr();
        PaymentLinkResponse response = guestRegistrationService.initPayment(request, ipAddress);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/status/{token}")
    public ResponseEntity<GuestRegistrationSessionResponse> getSessionStatus(
            @PathVariable String token
    ) {
        GuestRegistrationSessionResponse response = guestRegistrationService.getSessionStatus(token);
        return ResponseEntity.ok(response);
    }
}

