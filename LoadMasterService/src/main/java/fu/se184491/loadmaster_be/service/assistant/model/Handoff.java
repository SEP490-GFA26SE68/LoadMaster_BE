package fu.se184491.loadmaster_be.service.assistant.model;

/** {@code offered}: trợ lý đang hỏi có chuyển cho nhân viên không. {@code ticketCode}: mã yêu cầu hỗ trợ đã tạo. */
public record Handoff(boolean offered, String ticketCode) {

    public static Handoff none() {
        return new Handoff(false, null);
    }
}
