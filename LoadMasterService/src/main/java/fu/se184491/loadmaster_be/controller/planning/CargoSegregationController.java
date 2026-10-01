package fu.se184491.loadmaster_be.controller.planning;

import fu.se184491.loadmaster_be.dto.response.planning.SegregationResult;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.service.planning.CargoSegregationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/trips")
@RequiredArgsConstructor
public class CargoSegregationController {

    private final CargoSegregationService cargoSegregationService;
    private final CurrentUserService currentUserService;

    @GetMapping("/{id}/segregation")
    @PreAuthorize("hasAuthority('TRIP_MANAGE')")
    public SegregationResult checkTrip(@PathVariable Long id) {
        return cargoSegregationService.checkTrip(currentUserService.getCurrentCompanyId(), id);
    }
}
