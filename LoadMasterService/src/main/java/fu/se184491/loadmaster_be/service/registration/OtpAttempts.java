package fu.se184491.loadmaster_be.service.registration;

/** Giới hạn số lần nhập sai mã OTP, dùng chung cho đăng ký trả phí và đăng ký dùng thử. */
public final class OtpAttempts {

    public static final int MAX_OTP_ATTEMPTS = 5;

    private OtpAttempts() {
    }

    public static boolean exhausted(Integer failedAttempts) {
        return failedAttempts != null && failedAttempts >= MAX_OTP_ATTEMPTS;
    }

    public static int next(Integer failedAttempts) {
        return (failedAttempts == null ? 0 : failedAttempts) + 1;
    }
}
