package fu.se184491.loadmaster_be.service.assistant.extract;

import fu.se184491.loadmaster_be.dto.response.assistant.RequirementDraftResponse;

/** Kết quả một lượt đọc bản nháp: dữ liệu trả cho trang và số token của lần gọi mô hình (đã tính vào hạn mức ngày). */
public record RequirementDraftResult(RequirementDraftResponse data, int inputTokens, int outputTokens) {
}
