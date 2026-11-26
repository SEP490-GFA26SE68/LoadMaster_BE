package fu.se184491.loadmaster_be.dto.response.optimization;

import java.time.Instant;

/**
 * Tiến độ sống của một lần chạy tối ưu đang RUNNING (optimization_jobs.progress, V58; OptimizeService ghi).
 *
 * @param stage             BUILDING (các lượt EP + DBLF đầu) | IMPROVING (Lumo / EP_LUMO / GA / SA) | SELECTING (chọn ba
 *                          phương án A · B · C) | SAVING (kiểm lại và lưu kết quả)
 * @param algorithm         mã PlanAlgorithm đang chạy; null ở SELECTING / SAVING
 * @param layoutsTried      số cách xếp đã dựng xong tới lúc này
 * @param totalPackages     số kiện của bài toán
 * @param bestPlacedCount   số kiện xếp được nhiều nhất trong các cách xếp đã xong; null khi chưa có cách nào
 * @param bestVolumePercent tỷ lệ lấp đầy thể tích cao nhất (0–100) trong các cách xếp đã xong; null khi chưa có
 * @param updatedAt         lúc snapshot được ghi
 */
public record OptimizationJobProgressResponse(String stage, String algorithm, Integer layoutsTried,
                                              Integer totalPackages, Integer bestPlacedCount,
                                              Double bestVolumePercent, Instant updatedAt) {
}
