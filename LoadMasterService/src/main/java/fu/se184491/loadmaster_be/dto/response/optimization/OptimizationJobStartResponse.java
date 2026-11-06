package fu.se184491.loadmaster_be.dto.response.optimization;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Kết quả gửi job tối ưu: {@code jobId} là mã job (UUID) dùng để đọc trạng thái và phương án. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OptimizationJobStartResponse {
    private String jobId;
    private Long tripId;
    private String subscriptionTier;
}
