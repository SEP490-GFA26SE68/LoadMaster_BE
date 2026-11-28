package fu.se184491.loadmaster_be.service.vehicle;

import fu.se184491.loadmaster_be.constant.vehicle.VehicleBodyStyle;
import fu.se184491.loadmaster_be.dto.response.trip.TripVehicleConfigResponse;
import fu.se184491.loadmaster_be.entity.vehicle.Vehicle;
import fu.se184491.loadmaster_be.entity.vehicle.VehicleType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Kiểu thân xe đi từ loại xe đang gắn ra cấu hình xe ({@code /api/vehicles/{id}/config}, {@code /api/trips/{id}/vehicle-config},
 * xe chọn được khi đổi xe) dưới dạng tên của enum; trống khi chưa chọn hoặc không có loại xe.
 */
class VehicleBodyStyleMappingTest {

    private static Vehicle vehicle(VehicleBodyStyle bodyStyle) {
        VehicleType type = VehicleType.builder().id(3L).name("Thùng 6 m").innerLength(600).innerWidth(235).innerHeight(240)
                .maxPayloadKg(new BigDecimal("5000")).bodyStyle(bodyStyle).build();
        return Vehicle.builder().id(4L).name("Xe 01").licensePlate("51C-123.45").vehicleType(type).build();
    }

    @Test
    void vehicleConfigCarriesTheBodyStyleOfTheCurrentType() {
        TripVehicleConfigResponse config = TripVehicleConfigResponse.of(vehicle(VehicleBodyStyle.TRUCK_BOX));

        assertEquals("TRUCK_BOX", config.bodyStyle());
        assertEquals(600, config.innerLength());
    }

    @Test
    void vehicleConfigHasNoBodyStyleWhenTheTypeHasNone() {
        assertNull(TripVehicleConfigResponse.of(vehicle(null)).bodyStyle());
    }

    @Test
    void nameOfIsNullWithoutTypeOrStyle() {
        assertNull(VehicleBodyStyle.nameOf(null));
        assertNull(VehicleBodyStyle.nameOf(VehicleType.builder().build()));
        assertEquals("VAN_MINI_HOOD", VehicleBodyStyle.nameOf(VehicleType.builder().bodyStyle(VehicleBodyStyle.VAN_MINI_HOOD).build()));
    }

    @Test
    void theEightStylesMatchTheWebAndTheMigration() {
        // Cùng danh sách với ràng buộc chk_vehicle_types_body_style của V59 và VEHICLE_BODY_STYLES của web
        assertEquals(List.of("VAN_MINI_FLAT", "VAN_MINI_HOOD", "VAN_MID", "VAN_LARGE",
                        "TRUCK_BOX", "TRUCK_CANVAS", "TRUCK_FLATBED", "TRUCK_REEFER"),
                Arrays.stream(VehicleBodyStyle.values()).map(Enum::name).toList());
    }
}
