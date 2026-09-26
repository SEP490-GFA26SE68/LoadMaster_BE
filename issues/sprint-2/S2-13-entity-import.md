# S2-13 · Loại bỏ persistence ImportJob + ImportRowError

| Field | Value |
|-------|-------|
| **Sprint** | 2 |
| **Module** | Import |
| **Priority** | 🔴 Must |
| **Label** | `BE` |
| **Status** | 🔲 Todo — schema cleanup |

## Mô tả
`refactor_entity.md` v3.4 không định nghĩa `ImportJob` hoặc `ImportRowError`. Import phải chạy stateless/in-memory; lỗi dòng chỉ là DTO trong response.

## Acceptance Criteria
- [ ] Không tạo bảng/entity/repository `ImportJob`, `ImportRowError` hoặc enum `ImportJobStatus`
- [ ] Dùng DTO `ImportPreviewResponse` và `ImportRowErrorResponse` thuần, không có JPA annotation
- [ ] Preview không ghi DB; confirm chỉ ghi vào `orders` và `packages` trong transaction
- [ ] Nếu code/migration cũ đã tạo các bảng ngoài schema, tách việc xóa dữ liệu/migration thành thay đổi có kiểm soát

## Files cần cập nhật
- `dto/response/ImportPreviewResponse.java`
- `dto/response/ImportRowErrorResponse.java`
- `service/import_/ImportService.java`
