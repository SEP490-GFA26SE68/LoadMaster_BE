package fu.se184491.loadmaster_be.dto.response.trip;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Các xe của công ty để đổi xe cho một chuyến: hàng của chuyến (số kiện, kg, cm³) và từng xe kèm cấu hình, trạng thái, xe nào
 * đang chạy chuyến khác, và lý do xe không chở được hàng ({@code issues}: mã + tham số như app web).
 */
public record TripVehicleChoicesResponse(Cargo cargo, List<Choice> choices) {

    public record Cargo(int count, BigDecimal totalKg, BigDecimal totalCm3) {
    }

    public record Choice(TripVehicleConfigResponse vehicle, String status, boolean current, String busyTripCode,
                         boolean selectable, List<Issue> issues) {
    }

    public record Issue(String code, String severity, Map<String, Object> params) {
    }
}
