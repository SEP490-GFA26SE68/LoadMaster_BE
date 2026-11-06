package fu.se184491.loadmaster_be.constant.registration;

public enum RegistrationStatus {
    PENDING_EMAIL_VERIFY,
    EMAIL_VERIFIED,
    POLICY_ACCEPTED,
    PAYMENT_PENDING,
    PAYMENT_FAILED,
    COMPLETED,
    EXPIRED
}
