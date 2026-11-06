package fu.se184491.loadmaster_be.dto.request.monitoring;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Quản lý gia hạn giao cho một yêu cầu giao của chuyến sau khi sự cố đã được chuyển lên mình.
 *
 * <p>{@code newDeadline}: giờ Việt Nam, không múi giờ. {@code contactNote}: nội dung đã trao đổi
 * với khách, bắt buộc. Ba trường kiểm ở service để lỗi mang mã nghiệp vụ.</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RenegotiateDeadlineRequest {

    private Long requirementId;

    private LocalDateTime newDeadline;

    @Size(max = 500, message = "Ghi chú liên hệ tối đa 500 ký tự")
    private String contactNote;
}
