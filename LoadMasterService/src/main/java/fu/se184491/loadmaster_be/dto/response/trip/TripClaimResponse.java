package fu.se184491.loadmaster_be.dto.response.trip;

import com.fasterxml.jackson.annotation.JsonFormat;
import fu.se184491.loadmaster_be.constant.trip.TripClaimStatus;
import lombok.Builder;

import java.time.LocalDateTime;

@Builder
public record TripClaimResponse(
        Long id,
        Long tripId,
        Long driverUserId,
        String driverName,
        String driverPhone,
        TripClaimStatus status,
        String note,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime createdAt,
        Long decidedById,
        String decidedByName,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime decidedAt,
        String rejectReason
) {}
