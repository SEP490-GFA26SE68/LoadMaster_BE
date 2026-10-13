package fu.se184491.loadmaster_be.dto.request.monitoring;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DriverLocationRequest {

    /** Numeric trip PK or tripCode (e.g. TRIP-20260312-xxx). */
    @NotNull(message = "tripId không được để trống")
    private String tripId;

    @NotNull(message = "latitude không được để trống")
    private Double lat;

    @NotNull(message = "longitude không được để trống")
    private Double lng;

    private Double speedKmh;

    private Double heading;
}
