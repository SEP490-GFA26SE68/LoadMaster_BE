package fu.se184491.loadmaster_be.service.support;

import fu.se184491.loadmaster_be.entity.support.TrialAccountRequest;

public interface TrialPreValidationService {
    /**
     * Validates tax code, email, and company details against database records and rules.
     * If invalid, automatically rejects the request, marks handledByName as "AI tự xử lý",
     * saves to database, and triggers an automated notification email.
     * If valid, marks aiPrecheckStatus as PASSED and status as PENDING (for Manager review).
     *
     * @param request the trial account request to pre-validate
     * @return true if passed AI pre-validation, false otherwise
     */
    boolean validateAndProcess(TrialAccountRequest request);
}

