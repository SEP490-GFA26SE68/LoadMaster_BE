package fu.se184491.loadmaster_be.dto.request.support;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RejectTrialAccountRequest {

    public static final String TIMESTAMP_REGEX = "^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01]) ([01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]$";

    private String reason;

    @Pattern(regexp = TIMESTAMP_REGEX, message = "INVALID_TIMESTAMP_FORMAT")
    private String createdAt;

    @Pattern(regexp = TIMESTAMP_REGEX, message = "INVALID_TIMESTAMP_FORMAT")
    private String resolvedAt;

    @AssertTrue(message = "RESOLVED_BEFORE_CREATED")
    public boolean isResolvedAtAfterCreatedAt() {
        if (resolvedAt == null || resolvedAt.isBlank() || createdAt == null || createdAt.isBlank()) {
            return true;
        }
        return resolvedAt.trim().compareTo(createdAt.trim()) >= 0;
    }
}
