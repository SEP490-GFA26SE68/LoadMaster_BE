package fu.se184491.loadmaster_be.dto.request.registration;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GuestRegistrationInitRequest {

    @NotBlank(message = "Email không được để trống")
    @Email(message = "Email không đúng định dạng")
    @Size(max = 150, message = "Email tối đa 150 ký tự")
    private String email;

    @NotBlank(message = "Họ và tên không được để trống")
    @Size(max = 150, message = "Họ và tên tối đa 150 ký tự")
    private String fullName;

    @Builder.Default
    private String phoneCountry = "VN";

    @NotBlank(message = "PHONE_NUMBER_REQUIRED")
    @Size(max = 20, message = "INVALID_PHONE_NUMBER")
    private String phoneNumber;

    @NotBlank(message = "Tên công ty không được để trống")
    @Size(max = 255, message = "Tên công ty tối đa 255 ký tự")
    private String companyName;

    @NotBlank(message = "Mã công ty không được để trống")
    @Size(max = 50, message = "Mã công ty tối đa 50 ký tự")
    private String companyCode;

    @NotBlank(message = "TAX_CODE_REQUIRED")
    @Pattern(regexp = "^([0-9]{10}|[0-9]{10}-[0-9]{3}|[0-9]{13})$", message = "INVALID_TAX_CODE_FORMAT")
    @Size(max = 50, message = "INVALID_TAX_CODE_FORMAT")
    private String taxCode;

    @Size(max = 500, message = "Địa chỉ tối đa 500 ký tự")
    private String companyAddress;

    @AssertTrue(message = "INVALID_PHONE_NUMBER")
    public boolean isPhoneNumberValidForCountry() {
        if (phoneNumber == null || phoneNumber.isBlank()) {
            return true; // @NotBlank(message = "PHONE_NUMBER_REQUIRED") handles empty phoneNumber
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

    public static java.util.List<String> buildPhoneVariants(String rawPhone) {
        if (rawPhone == null || rawPhone.isBlank()) {
            return java.util.List.of();
        }
        String cleaned = rawPhone.replaceAll("[\\s.-]", "");
        if (cleaned.matches("^(0[35789][0-9]{8}|(\\+?84)[35789][0-9]{8})$")) {
            String tail9 = cleaned.substring(cleaned.length() - 9);
            return java.util.List.of("0" + tail9, "84" + tail9, "+84" + tail9, rawPhone.trim());
        }
        return java.util.List.of(cleaned, rawPhone.trim());
    }
}
