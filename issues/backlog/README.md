# Backlog — Schema alignment v3.4

> `refactor_entity.md` là source of truth cho entity, bảng, field và foreign key. Không tạo entity/bảng ngoài data dictionary nếu chưa cập nhật đặc tả trước.

---

## Các đề xuất cũ đã loại khỏi backlog

| Đề xuất cũ | Quyết định theo schema v3.4 |
|---|---|
| `ImportJob`, `ImportRowError` | Không tạo entity. Import preview chạy in-memory; lỗi dòng là response DTO. Confirm chỉ ghi `Order` và `Package`. |
| `OptimizationMetric` | Không tạo entity. Dùng `LoadPlan.volumeUtilizationPercent`, `OptimizationJob.executionTimeMs`, `CenterOfGravity`; các count được query từ bảng con. |
| `PlacementConfirmation`, `Deviation` | Không tạo entity. Theo dõi execution ở mức `LoadingExecution`; cần chi tiết hơn thì sửa data dictionary trước. |
| `UnloadConfirmation` | Không tạo entity. Driver đọc manifest qua `DeliveryStop → Order → Package`; hoàn tất cập nhật `Trip.status` và ghi `AuditLog`. |
| `LoadPlan.version` | Dùng field chuẩn `planVersion`. |
| `LoadPlan.parentPlanId`, `LoadPlan.status` | Không thêm. Các version liên hệ qua cùng Trip (`LoadPlan → OptimizationJob → Trip`); trạng thái duyệt dùng `isApproved`, `approvedAt`, `approvedByUserId`. |

## Quy tắc mở rộng schema

- Issue mới cần persistence phải nêu table name, Java entity/repository, toàn bộ field, datatype, nullability, unique constraint, FK và `ON DELETE`.
- Chỉ implement sau khi thay đổi được đưa vào `refactor_entity.md` và migration tương ứng.
- DTO/engine payload có thể có field tính toán tạm thời, nhưng phải ghi rõ field nào không được persist.
