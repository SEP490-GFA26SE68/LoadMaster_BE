package fu.se184491.loadmaster_be.dto.response.assistant;

import com.fasterxml.jackson.annotation.JsonInclude;
import fu.se184491.loadmaster_be.constant.planning.DeliveryRequirementPriority;

import java.util.List;

/**
 * Bản nháp yêu cầu giao đọc từ nội dung dán vào. Máy chủ không tạo gì: trang dùng bản nháp để điền sẵn form tạo yêu
 * cầu giao, người dùng tự chọn khách gửi hàng, xác nhận rồi bấm lưu. Trường văn bản không nêu là {@code null};
 * {@code null} luôn được ghi ra JSON.
 *
 * @param destinationText      nơi giao (tên nơi nhận + địa chỉ, một dòng, tối đa 255 ký tự)
 * @param deadline             hạn giao theo giờ Việt Nam, dạng {@code yyyy-MM-dd'T'HH:mm:ss}, luôn ở tương lai lúc đọc
 * @param note                 ghi chú cho lần giao, tối đa 500 ký tự
 * @param customerNameGuess    tên bên gửi hàng văn bản nêu — chỉ là gợi ý, máy chủ không tra khách nào
 * @param packages             hàng văn bản liệt kê, chỉ để người dùng đối chiếu
 * @param missing              trường cần người dùng bổ sung, theo thứ tự {@code destinationText}, {@code deadline},
 *                             {@code priority}, {@code expectedPackageCount}
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record RequirementDraftResponse(String destinationText, String deadline, DeliveryRequirementPriority priority,
                                       Integer expectedPackageCount, String note, String customerNameGuess,
                                       List<DraftPackage> packages, List<String> missing) {

    /** Một loại hàng: kích thước (cm) và khối lượng (kg) của một kiện; số không nêu là {@code null}. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record DraftPackage(String description, Integer quantity, Double lengthCm, Double widthCm,
                               Double heightCm, Double weightKg) {
    }
}
