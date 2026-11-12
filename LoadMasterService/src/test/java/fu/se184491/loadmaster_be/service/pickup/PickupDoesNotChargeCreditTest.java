package fu.se184491.loadmaster_be.service.pickup;

import fu.se184491.loadmaster_be.service.credit.CreditService;
import fu.se184491.loadmaster_be.service.optimization.OptimizationEngineClient;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Xếp kiện nhận dọc đường vào chỗ trống chạy ngay trong máy chủ, không gọi dịch vụ tối ưu và không trừ credit. */
class PickupDoesNotChargeCreditTest {

    @Test
    void pickupServicesHaveNoCreditOrEngineDependency() {
        for (Class<?> type : List.of(fu.se184491.loadmaster_be.service.pickup.impl.PickupRequestServiceImpl.class, PickupContextBuilder.class, PickupRules.class,
                FreedSpacePacker.class, PickupDriverService.class)) {
            for (Field field : type.getDeclaredFields()) {
                assertThat(CreditService.class.isAssignableFrom(field.getType()))
                        .as(type.getSimpleName() + "." + field.getName()).isFalse();
                assertThat(OptimizationEngineClient.class.isAssignableFrom(field.getType()))
                        .as(type.getSimpleName() + "." + field.getName()).isFalse();
            }
        }
    }
}
