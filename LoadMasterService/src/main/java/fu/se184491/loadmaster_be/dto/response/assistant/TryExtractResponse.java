package fu.se184491.loadmaster_be.dto.response.assistant;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Giá trị cho form "Thử với hàng của bạn" đọc từ mô tả của khách. Đơn vị cm / kg. Trường không rõ hoặc ngoài giới hạn
 * của form là {@code null} và có đường dẫn trong {@code missing}; {@code null} luôn được ghi ra JSON.
 *
 * @param truck       {@code null} khi mô tả không nói gì về xe
 * @param assumptions câu ngắn theo ngôn ngữ của trang, mỗi câu một giả định đã làm
 * @param missing     đường dẫn trường cần khách nhập thêm, theo thứ tự xe → kiện → {@code cartonKinds} →
 *                    {@code totalQuantity}
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record TryExtractResponse(Truck truck, List<Carton> cartons, List<String> assumptions, List<String> missing) {

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Truck(Double lengthCm, Double widthCm, Double heightCm, Double payloadKg) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Carton(Double lengthCm, Double widthCm, Double heightCm, Double weightKg, Integer quantity,
                         Integer stop) {
    }
}
