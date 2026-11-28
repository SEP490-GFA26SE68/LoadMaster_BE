package fu.se184491.loadmaster_be.dto.response.optimization;

/**
 * Một dòng của bảng phân rã điểm J.
 *
 * @param code         mã thành phần (S1, S2, …)
 * @param name         tên thành phần
 * @param value        Vₖ
 * @param weight       wₖ
 * @param contribution đóng góp vào J: −100·wₖ·Vₖ
 * @param computed     false: thành phần không tính được (vd xe thiếu dữ liệu trục)
 */
public record PlanScoreComponentResponse(
        String code,
        String name,
        Double value,
        Double weight,
        Double contribution,
        Boolean computed) {
}
