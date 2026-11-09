package fu.se184491.loadmaster_be.dto.response.driver;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Sự cố vừa ghi cho một kiện. {@code unloaded}: kiện còn được tính đã dỡ sau sự cố (khách từ chối thì không). */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeliveryIssueResponse {

    private Long issueId;
    private Long packageId;
    private Long stopId;
    private String kind;
    private String note;
    private LocalDateTime reportedAt;
    private Boolean unloaded;
}
