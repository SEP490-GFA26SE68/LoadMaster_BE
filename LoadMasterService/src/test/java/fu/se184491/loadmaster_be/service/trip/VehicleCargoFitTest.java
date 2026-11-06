package fu.se184491.loadmaster_be.service.trip;

import fu.se184491.loadmaster_be.entity.cargo.CargoPackage;
import fu.se184491.loadmaster_be.entity.cargo.PackageType;
import fu.se184491.loadmaster_be.entity.vehicle.VehicleType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VehicleCargoFitTest {

    private static VehicleType truck() {
        return VehicleType.builder().innerLength(600).innerWidth(240).innerHeight(240)
                .doorWidth(230).doorHeight(230).maxPayloadKg(BigDecimal.valueOf(5000)).build();
    }

    private static CargoPackage box(String code, double length, double width, double height, double kg,
                                    PackageType type) {
        return CargoPackage.builder().packageCode(code).packageType(type)
                .actualLength(BigDecimal.valueOf(length)).actualWidth(BigDecimal.valueOf(width))
                .actualHeight(BigDecimal.valueOf(height)).actualWeightKg(BigDecimal.valueOf(kg)).build();
    }

    @Test
    void cargoThatFitsHasNoIssue() {
        assertThat(VehicleCargoFit.issues(truck(), List.of(box("PK-1", 120, 100, 100, 300, null)))).isEmpty();
    }

    @Test
    void packageWiderThanTheDoorIsTooLarge() {
        // Lọt lòng thùng (235 < 240) nhưng không qua cửa rộng 230, và không có loại kiện nên không xoay được.
        List<VehicleCargoFit.Issue> issues =
                VehicleCargoFit.issues(truck(), List.of(box("PK-WIDE", 100, 235, 100, 50, null)));

        assertThat(issues).extracting(VehicleCargoFit.Issue::code).containsExactly("CARGO_TOO_LARGE");
        assertThat(issues.get(0).params()).containsEntry("count", 1).containsEntry("packageIds", List.of("PK-WIDE"));
    }

    @Test
    void rotationAllowedByThePackageTypeLetsItThroughTheDoor() {
        PackageType turnable = PackageType.builder().rotationAllowed(true)
                .allowRotateX(false).allowRotateY(false).allowRotateZ(true).build();

        assertThat(VehicleCargoFit.issues(truck(), List.of(box("PK-WIDE", 100, 235, 100, 50, turnable)))).isEmpty();
    }

    @Test
    void heavyCargoExceedsPayloadAndAxleCapacity() {
        VehicleType withAxles = truck();
        withAxles.setFrontAxleX(BigDecimal.valueOf(-100));
        withAxles.setRearAxleX(BigDecimal.valueOf(400));
        withAxles.setFrontAxleLimitKg(BigDecimal.valueOf(3000));
        withAxles.setRearAxleLimitKg(BigDecimal.valueOf(4000));
        withAxles.setEmptyFrontAxleLoadKg(BigDecimal.valueOf(1500));
        withAxles.setEmptyRearAxleLoadKg(BigDecimal.valueOf(1000));

        List<VehicleCargoFit.Issue> issues = VehicleCargoFit.issues(withAxles,
                List.of(box("PK-1", 100, 100, 100, 3000, null), box("PK-2", 100, 100, 100, 2500, null)));

        assertThat(issues).extracting(VehicleCargoFit.Issue::code)
                .containsExactly("CARGO_WEIGHT_EXCEEDED", "AXLE_CAPACITY_EXCEEDED");
        assertThat(issues.get(0).params()).containsEntry("totalKg", 5500.0).containsEntry("overKg", 500.0);
        assertThat(issues.get(1).params()).containsEntry("capacityKg", 4500.0).containsEntry("overKg", 1000.0);
    }

    @Test
    void totalVolumeLargerThanTheBodyIsRejected() {
        VehicleType small = VehicleType.builder().innerLength(200).innerWidth(100).innerHeight(100).build();

        List<VehicleCargoFit.Issue> issues = VehicleCargoFit.issues(small,
                List.of(box("PK-1", 100, 100, 100, 10, null), box("PK-2", 100, 100, 100, 10, null),
                        box("PK-3", 100, 100, 100, 10, null)));

        assertThat(issues).extracting(VehicleCargoFit.Issue::code).containsExactly("CARGO_VOLUME_EXCEEDED");
    }
}
