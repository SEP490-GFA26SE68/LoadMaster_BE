package fu.se184491.loadmaster_be.dto.request.support;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GuestTrialInitRequest {

    public static final String TIMESTAMP_REGEX = "^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01]) ([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]$";

    @NotBlank(message = "Tên doanh nghiệp không được để trống")
    private String companyName;

    @NotBlank(message = "TAX_CODE_REQUIRED")
    @Pattern(regexp = "^([0-9]{10}|[0-9]{10}-[0-9]{3}|[0-9]{13})$", message = "INVALID_TAX_CODE_FORMAT")
    private String taxCode;

    private String companyAddress;

    @NotBlank(message = "Họ và tên người đại diện không được để trống")
    private String fullName;

    @NotBlank(message = "Email không được để trống")
    @Email(message = "Email không đúng định dạng")
    private String email;

    @Builder.Default
    private String phoneCountry = "VN";

    private String phoneNumber;

    private Long interestedPlanId;

    private String note;

    @Pattern(regexp = TIMESTAMP_REGEX, message = "INVALID_TIMESTAMP_FORMAT")
    private String createdAt;

    @Pattern(regexp = TIMESTAMP_REGEX, message = "INVALID_TIMESTAMP_FORMAT")
    private String expiresAt;

    @Pattern(regexp = TIMESTAMP_REGEX, message = "INVALID_TIMESTAMP_FORMAT")
    private String otpExpiresAt;

    @Pattern(regexp = TIMESTAMP_REGEX, message = "INVALID_TIMESTAMP_FORMAT")
    private String otpResendAllowedAt;

    @AssertTrue(message = "INVALID_PHONE_NUMBER")
    public boolean isPhoneNumberValidForCountry() {
        if (phoneNumber == null || phoneNumber.isBlank()) {
            return true;
        }
        String cleaned = phoneNumber.replaceAll("[\\s.-]", "");
        String country = (phoneCountry == null || phoneCountry.isBlank())
                ? "VN"
                : phoneCountry.trim().toUpperCase();
        return switch (country) {
            case "VN" -> {
                if (!cleaned.matches("^(0[35789][0-9]{8}|(\\+?84)[35789][0-9]{8})$")) {
                    yield false;
                }
                String last8 = cleaned.substring(cleaned.length() - 8);
                yield !last8.matches("^(\\d)\\1{7}$");
            }
            case "SG" -> cleaned.matches("^((\\+?65)?[89][0-9]{7})$");
            case "US" -> cleaned.matches("^((\\+?1)?[2-9][0-9]{9})$");
            case "JP" -> cleaned.matches("^((0|\\+?81)[789]0[0-9]{8})$");
            case "KR" -> cleaned.matches("^((0|\\+?82)10[0-9]{8})$");
            default -> cleaned.matches("^\\+?[0-9]{8,15}$");
        };
    }

    @AssertTrue(message = "EXPIRES_BEFORE_CREATED")
    public boolean isSessionTimestampsValid() {
        if (createdAt == null || createdAt.isBlank()) {
            return true;
        }
        String base = createdAt.trim();
        if (expiresAt != null && !expiresAt.isBlank() && expiresAt.trim().compareTo(base) < 0) {
            return false;
        }
        if (otpExpiresAt != null && !otpExpiresAt.isBlank() && otpExpiresAt.trim().compareTo(base) < 0) {
            return false;
        }
        if (otpResendAllowedAt != null && !otpResendAllowedAt.isBlank() && otpResendAllowedAt.trim().compareTo(base) < 0) {
            return false;
        }
        return true;
    }
}

