package fu.se184491.loadmaster_be.service.assistant.tool.data;

import com.fasterxml.jackson.annotation.JsonInclude;
import fu.se184491.loadmaster_be.constant.account.UserRole;
import fu.se184491.loadmaster_be.constant.trip.TripClaimStatus;
import fu.se184491.loadmaster_be.dto.response.trip.OpenTripResponse;
import fu.se184491.loadmaster_be.service.assistant.tool.AssistantTool;
import fu.se184491.loadmaster_be.service.assistant.tool.ToolPage;
import fu.se184491.loadmaster_be.service.assistant.tool.ToolResults;
import fu.se184491.loadmaster_be.service.trip.TripClaimService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

/**
 * Chuyến đang mở cho tài xế xin nhận, đọc qua đúng hàm của {@code GET /api/driver/open-trips}: chuyến Nháp hoặc Đã
 * lập kế hoạch chưa có tài xế, trong công ty của tài xế đang hỏi, kèm phiếu xin nhận gần nhất của chính họ. Chỉ đọc:
 * xin nhận hay rút phiếu là việc tài xế tự bấm trong app, trợ lý không làm thay. Không trả mã số nội bộ.
 */
@Component
@RequiredArgsConstructor
public class OpenTripsTool implements AssistantTool {

    private final TripClaimService claims;

    /** {@code myClaimStatus}: phiếu gần nhất của tài xế với chuyến (PENDING chờ duyệt, REJECTED bị từ chối, CANCELLED đã rút). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OpenTrip(String tripCode, String tripName, LocalDateTime departureTime, String originWarehouse,
                           Integer stopCount, TripClaimStatus myClaimStatus, String myRejectReason) {
    }

    @Override
    public Set<UserRole> roles() {
        return EnumSet.of(UserRole.DRIVER);
    }

    @Tool(name = "list_open_trips", description = """
            Chuyến đang mở trong công ty của tài xế đang hỏi — chuyến chưa có tài xế, còn Nháp hoặc Đã lập kế hoạch \
            — theo giờ xuất phát: mã, tên tuyến, giờ xuất phát, kho xuất phát, số điểm giao và phiếu xin nhận gần nhất \
            của chính tài xế (PENDING chờ điều phối viên duyệt, REJECTED bị từ chối kèm lý do, CANCELLED đã rút). \
            Chỉ đọc: bạn không xin nhận hay rút phiếu thay tài xế — họ tự bấm "Xin nhận chuyến" hoặc "Rút phiếu" ở \
            tab "Chuyến đang mở" của app di động. Dùng khi tài xế hỏi có chuyến nào nhận được hay phiếu xin nhận của \
            mình tới đâu.""")
    public ToolPage<OpenTrip> listOpenTrips() {
        return ToolResults.page(claims.getOpenTrips().stream().map(OpenTripsTool::rowOf).toList(),
                ToolResults.MAX_ROWS);
    }

    private static OpenTrip rowOf(OpenTripResponse trip) {
        return new OpenTrip(trip.tripCode(), trip.tripName(), trip.departureTime(), trip.originWarehouse(),
                trip.stopCount(), trip.myClaimStatus(), trip.myRejectReason());
    }
}
