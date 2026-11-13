package fu.se184491.loadmaster_be.dto.request.vehicle;

import jakarta.validation.constraints.Size;

public record VehicleMaintenanceRequest(@Size(max = 500) String note) {
}
