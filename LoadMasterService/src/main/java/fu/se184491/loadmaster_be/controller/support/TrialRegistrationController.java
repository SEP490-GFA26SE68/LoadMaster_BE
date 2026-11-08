package fu.se184491.loadmaster_be.controller.support;

import fu.se184491.loadmaster_be.dto.request.support.GuestTrialInitRequest;
import fu.se184491.loadmaster_be.dto.request.support.GuestTrialResendOtpRequest;
import fu.se184491.loadmaster_be.dto.request.support.GuestTrialVerifyOtpRequest;
import fu.se184491.loadmaster_be.dto.response.support.GuestTrialInitResponse;
import fu.se184491.loadmaster_be.dto.response.support.GuestTrialVerifyResponse;
import fu.se184491.loadmaster_be.service.support.TrialRegistrationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/trial-registration")
@RequiredArgsConstructor
public class TrialRegistrationController {

    private final TrialRegistrationService trialRegistrationService;

    @PostMapping("/init")
    public ResponseEntity<GuestTrialInitResponse> initTrialRegistration(
            @Valid @RequestBody GuestTrialInitRequest request
    ) {
        GuestTrialInitResponse response = trialRegistrationService.initTrialRegistration(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/verify-otp")
    public ResponseEntity<GuestTrialVerifyResponse> verifyOtp(
            @Valid @RequestBody GuestTrialVerifyOtpRequest request
    ) {
        GuestTrialVerifyResponse response = trialRegistrationService.verifyOtp(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/resend-otp")
    public ResponseEntity<GuestTrialInitResponse> resendOtp(
            @Valid @RequestBody GuestTrialResendOtpRequest request
    ) {
        GuestTrialInitResponse response = trialRegistrationService.resendOtp(request);
        return ResponseEntity.ok(response);
    }
}
