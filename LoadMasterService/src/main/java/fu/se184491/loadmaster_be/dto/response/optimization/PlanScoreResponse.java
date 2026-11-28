package fu.se184491.loadmaster_be.dto.response.optimization;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Điểm J của phương án theo hồ sơ ưu tiên đã chọn nó, kèm phân rã. Dịch vụ tối ưu tính và lưu khi cờ PLAN_SCORE_J bật
 * (cột {@code load_plans.plan_score}, V57); Java chỉ đọc lại.
 *
 * @param profile        hồ sơ (A | B | C | default)
 * @param profileName    tên hồ sơ
 * @param assumedWeights trọng số còn là số giả định
 * @param j              điểm J; trống khi dịch vụ tối ưu không tính được số hữu hạn
 * @param uVol           tỷ lệ thể tích đã dùng (0..1)
 * @param uN             tỷ lệ số kiện đã xếp (0..1)
 * @param wVol           trọng số của uVol
 * @param wN             trọng số của uN
 * @param components     từng thành phần phạt Vₖ, wₖ và đóng góp −100·wₖ·Vₖ
 */
public record PlanScoreResponse(
        String profile,
        String profileName,
        Boolean assumedWeights,
        Double j,
        // Tên có chữ hoa thứ hai: khai tường minh để JSON giữ đúng "uVol", "uN", "wVol", "wN".
        @JsonProperty("uVol") Double uVol,
        @JsonProperty("uN") Double uN,
        @JsonProperty("wVol") Double wVol,
        @JsonProperty("wN") Double wN,
        List<PlanScoreComponentResponse> components) {
}
