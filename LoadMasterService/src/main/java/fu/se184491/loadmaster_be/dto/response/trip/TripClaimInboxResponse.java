package fu.se184491.loadmaster_be.dto.response.trip;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;

import java.time.LocalDateTime;

@Builder
public record TripClaimInboxResponse(
        Long claimId,
        Long tripId,
        String tripCode,
        String tripName,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime departureTime,
        String originWarehouse,
        int stopCount,
        Long driverUserId,
        String driverName,
        String driverPhone,
        String note,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime createdAt
) {}
