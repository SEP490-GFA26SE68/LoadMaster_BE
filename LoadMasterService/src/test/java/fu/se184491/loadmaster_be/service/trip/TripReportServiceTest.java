package fu.se184491.loadmaster_be.service.trip;

import fu.se184491.loadmaster_be.constant.driver.ManualConfirmStatus;
import fu.se184491.loadmaster_be.constant.driver.ManualConfirmStep;
import fu.se184491.loadmaster_be.constant.trip.TripStatus;
import fu.se184491.loadmaster_be.constant.warehouse.StagingMethod;
import fu.se184491.loadmaster_be.dto.response.trip.TripReportResponse;
import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.driver.ManualUnloadConfirmation;
import fu.se184491.loadmaster_be.entity.driver.UnloadConfirmation;
import fu.se184491.loadmaster_be.entity.optimization.PackagePlacement;
import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.entity.warehouse.PlacementConfirmation;
import fu.se184491.loadmaster_be.entity.warehouse.StagingConfirmation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TripReportServiceTest {

    @Test
    void reportExistsForDeliveredTripsAndTripsCancelledOnTheRoad() {
        assertThat(TripReportService.reportAvailable(Trip.builder().status(TripStatus.DELIVERED).build())).isTrue();
        assertThat(TripReportService.reportAvailable(Trip.builder().status(TripStatus.CANCELLED)
                .cancelledFromStatus(TripStatus.IN_TRANSIT).build())).isTrue();
        assertThat(TripReportService.reportAvailable(Trip.builder().status(TripStatus.CANCELLED)
                .cancelledFromStatus(TripStatus.LOADING).build())).isFalse();
        assertThat(TripReportService.reportAvailable(Trip.builder().status(TripStatus.CANCELLED).build())).isFalse();
        assertThat(TripReportService.reportAvailable(Trip.builder().status(TripStatus.IN_TRANSIT).build())).isFalse();
    }

    @Test
    void verificationsSplitLabelAndManualPerStep() {
        CargoPackage first = CargoPackage.builder().id(1L).build();
        CargoPackage second = CargoPackage.builder().id(2L).build();
        PackagePlacement placedFirst = PackagePlacement.builder().id(11L).build();
        PackagePlacement placedSecond = PackagePlacement.builder().id(12L).build();

        List<StagingConfirmation> staged = List.of(
                StagingConfirmation.builder().cargoPackage(first).method(StagingMethod.QR).build(),
                StagingConfirmation.builder().cargoPackage(second).method(StagingMethod.MANUAL).build());
        List<PlacementConfirmation> placed = List.of(
                PlacementConfirmation.builder().placement(placedFirst).build(),
                PlacementConfirmation.builder().placement(placedSecond).build());
        List<UnloadConfirmation> unloads = List.of(
                UnloadConfirmation.builder().cargoPackage(first).build(),
                UnloadConfirmation.builder().cargoPackage(second).build());
        List<ManualUnloadConfirmation> manuals = List.of(
                ManualUnloadConfirmation.builder().step(ManualConfirmStep.LOADING).placement(placedSecond)
                        .status(ManualConfirmStatus.APPROVED).build(),
                // Bản ghi cũ không ghi bước là dỡ hàng; xác nhận tay bị từ chối không tính
                ManualUnloadConfirmation.builder().cargoPackage(first).status(ManualConfirmStatus.PENDING).build(),
                ManualUnloadConfirmation.builder().step(ManualConfirmStep.UNLOADING).cargoPackage(second)
                        .status(ManualConfirmStatus.REJECTED).build());

        List<TripReportResponse.Verification> rows = TripReportService.verifications(staged, placed, unloads, manuals);

        assertThat(rows).containsExactly(
                new TripReportResponse.Verification("STAGING", 1, 1),
                new TripReportResponse.Verification("LOADING", 1, 1),
                new TripReportResponse.Verification("UNLOADING", 1, 1));
    }
}
