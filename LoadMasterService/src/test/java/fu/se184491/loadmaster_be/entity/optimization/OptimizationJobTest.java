package fu.se184491.loadmaster_be.entity.optimization;

import fu.se184491.loadmaster_be.entity.trip.Trip;
import fu.se184491.loadmaster_be.entity.vehicle.Vehicle;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class OptimizationJobTest {

    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

    @Test
    void jobCreatedByJavaGetsVietnamCreationTime() {
        OptimizationJob job = OptimizationJob.builder().jobUuid("rerun").build();

        job.fillCreatedAt();

        LocalDateTime now = LocalDateTime.now(VIETNAM);
        assertThat(job.getCreatedAt()).isNotNull();
        assertThat(Duration.between(job.getCreatedAt(), now).abs()).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void existingCreationTimeIsKept() {
        LocalDateTime written = LocalDateTime.of(2026, 11, 12, 9, 30);
        OptimizationJob job = OptimizationJob.builder().createdAt(written).build();

        job.fillCreatedAt();

        assertThat(job.getCreatedAt()).isEqualTo(written);
    }

    @Test
    void jobRecordsTheVehicleTheTripHasWhenItIsCreated() {
        Trip trip = new Trip();
        trip.setVehicle(Vehicle.builder().id(42L).build());

        assertThat(OptimizationJob.vehicleIdOf(trip)).isEqualTo(42L);
    }

    @Test
    void jobWithoutTripOrVehicleHasNoVehicle() {
        assertThat(OptimizationJob.vehicleIdOf(null)).isNull();
        assertThat(OptimizationJob.vehicleIdOf(new Trip())).isNull();
    }
}
