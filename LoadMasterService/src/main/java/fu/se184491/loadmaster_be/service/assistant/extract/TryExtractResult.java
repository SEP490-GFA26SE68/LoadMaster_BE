package fu.se184491.loadmaster_be.service.assistant.extract;

import fu.se184491.loadmaster_be.dto.response.assistant.TryExtractResponse;

/** Kết quả một lượt trích xuất: dữ liệu trả cho trang và số token của lần gọi mô hình (đã tính vào hạn mức ngày). */
public record TryExtractResult(TryExtractResponse data, int inputTokens, int outputTokens) {
}
