package fu.se184491.loadmaster_be.service.support;

import fu.se184491.loadmaster_be.dto.request.support.GuestTrialInitRequest;
import fu.se184491.loadmaster_be.dto.request.support.GuestTrialResendOtpRequest;
import fu.se184491.loadmaster_be.dto.request.support.GuestTrialVerifyOtpRequest;
import fu.se184491.loadmaster_be.dto.response.support.GuestTrialInitResponse;
import fu.se184491.loadmaster_be.dto.response.support.GuestTrialVerifyResponse;

public interface TrialRegistrationService {
    GuestTrialInitResponse initTrialRegistration(GuestTrialInitRequest request);
    GuestTrialVerifyResponse verifyOtp(GuestTrialVerifyOtpRequest request);
    GuestTrialInitResponse resendOtp(GuestTrialResendOtpRequest request);
}

