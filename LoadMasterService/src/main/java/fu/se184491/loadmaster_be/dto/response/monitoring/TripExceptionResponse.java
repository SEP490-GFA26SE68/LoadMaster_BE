package fu.se184491.loadmaster_be.dto.response.monitoring;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TripExceptionResponse {

    private String id;
    private String tripId;
    private String type;
    private String description;
    private Integer delayMinutes;
    private String status;
    private Integer stopNumber;
    /** ISO-8601 */
    private String reportedAt;
    private String reportedBy;
    private EscalationInfo escalation;
    private ResolutionInfo resolution;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class EscalationInfo {
        private String reason;
        /** ISO-8601 */
        private String at;
        private String by;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ResolutionInfo {
        /** ISO-8601 */
        private String at;
        private String by;
        private String note;
    }
}
