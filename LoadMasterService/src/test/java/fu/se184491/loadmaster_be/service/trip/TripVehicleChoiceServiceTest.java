package fu.se184491.loadmaster_be.service.trip;

import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.constant.vehicle.VehicleStatus;
import fu.se184491.loadmaster_be.dto.response.trip.TripVehicleChoicesResponse;
import fu.se184491.loadmaster_be.dto.response.trip.TripVehicleChoicesResponse.Choice;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.entity.vehicle.Vehicle;
import fu.se184491.loadmaster_be.entity.vehicle.VehicleType;
import fu.se184491.loadmaster_be.exception.AppException;
import fu.se184491.loadmaster_be.exception.ErrorCode;
import fu.se184491.loadmaster_be.helpers.CurrentUserService;
import fu.se184491.loadmaster_be.repository.trip.TripRepository;
import fu.se184491.loadmaster_be.repository.vehicle.VehicleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TripVehicleChoiceServiceTest {

    @Mock TripRepository tripRepository;
    @Mock VehicleRepository vehicleRepository;
    @Mock TripPackageResolver tripPackageResolver;
    @Mock CurrentUserService currentUserService;

    @InjectMocks TripVehicleChoiceService service;

    private static Vehicle vehicle(long id, String name, int payloadKg, VehicleStatus status) {
        VehicleType type = VehicleType.builder().id(id).innerLength(600).innerWidth(240).innerHeight(240)
                .maxPayloadKg(BigDecimal.valueOf(payloadKg)).obstacles(new ArrayList<>()).build();
        return Vehicle.builder().id(id).name(name).status(status).vehicleType(type).build();
    }

    @BeforeEach
    void setUp() {
        when(currentUserService.getCurrentCompanyId()).thenReturn(1L);
        Vehicle current = vehicle(3L, "Xe A", 5000, VehicleStatus.AVAILABLE);
        when(tripRepository.findByIdAndCompanyId(10L, 1L))
                .thenReturn(Optional.of(Trip.builder().id(10L).vehicle(current).build()));
        when(tripPackageResolver.packagesOfTrip(10L)).thenReturn(List.of(CargoPackage.builder().packageCode("PK-1")
                .actualLength(BigDecimal.valueOf(100)).actualWidth(BigDecimal.valueOf(100))
                .actualHeight(BigDecimal.valueOf(100)).actualWeightKg(BigDecimal.valueOf(800)).build()));
        Vehicle busy = vehicle(4L, "Xe B", 5000, VehicleStatus.AVAILABLE);
        when(tripRepository.findByCompanyIdAndStatus(1L, TripStatus.LOADING))
                .thenReturn(List.of(Trip.builder().id(11L).tripCode("TRIP-011").vehicle(busy).build()));
        when(tripRepository.findByCompanyIdAndStatus(1L, TripStatus.IN_TRANSIT)).thenReturn(List.of());
        when(vehicleRepository.findByCompanyId(eq(1L), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(
                current, busy,
                vehicle(5L, "Xe C", 500, VehicleStatus.AVAILABLE),
                vehicle(6L, "Xe D", 5000, VehicleStatus.MAINTENANCE),
                vehicle(7L, "Xe E", 5000, VehicleStatus.AVAILABLE))));
    }

    @Test
    void choicesSayWhyEachVehicleCanOrCannotBeChosen() {
        TripVehicleChoicesResponse response = service.choices(10L);

        assertThat(response.cargo().count()).isEqualTo(1);
        assertThat(response.cargo().totalKg()).isEqualByComparingTo("800");
        List<Choice> choices = response.choices();
        assertThat(choices).extracting(choice -> choice.vehicle().vehicleName())
                .containsExactly("Xe E", "Xe A", "Xe B", "Xe C", "Xe D");
        assertThat(choices.get(0).selectable()).isTrue();
        assertThat(choices.get(1).current()).isTrue();
        assertThat(choices.get(2).busyTripCode()).isEqualTo("TRIP-011");
        assertThat(choices.get(3).issues()).extracting(TripVehicleChoicesResponse.Issue::code)
                .containsExactly("CARGO_WEIGHT_EXCEEDED");
        assertThat(choices.get(4).status()).isEqualTo("MAINTENANCE");
        assertThat(choices.subList(1, 5)).noneMatch(Choice::selectable);
    }

    @Test
    void tripOfAnotherCompanyIsNotFound() {
        assertThatThrownBy(() -> service.choices(99L))
                .extracting(error -> ((AppException) error).getErrorCode())
                .isEqualTo(ErrorCode.TRIP_NOT_FOUND);
    }
}
