# LoadMaster Issues — Schema v3.4

Các issue trong thư mục này dùng `../refactor_entity.md` làm source of truth cho persistence.

## Quy ước bắt buộc

- Entity/repository/table/field/FK/nullability/unique/`ON DELETE` phải khớp data dictionary v3.4.
- ID persistence dùng `Long`/`BIGSERIAL` hoặc FK `BIGINT`; DTO Python tương ứng dùng `int`.
- Kích thước dùng `Integer` theo mm; khối lượng/tải trọng dùng `BigDecimal DECIMAL(10,2)` theo kg; tiền dùng `BigDecimal DECIMAL(15,2)` theo VNĐ.
- Một User có đúng một Role qua `users.role_id`; không có `UserRole`.
- Multi-tenancy dùng shared DB và `company_id`; mọi query nghiệp vụ phải kiểm soát tenant.
- Không tạo entity ngoài 27 entity đã liệt kê. Muốn thêm persistence phải cập nhật data dictionary và migration trước.

## Những thay đổi chính so với backlog cũ

- Login/User dùng `email`, `status`, một `roleId`; không dùng `username`, `enabled`, `roles[]`.
- Tên entity chuẩn là `Order` và `Package`, không phải `TransportOrder`/`CargoPackage`.
- Import preview là stateless; không có `ImportJob`/`ImportRowError` entity.
- Optimization dùng numeric ID; placement chỉ persist tọa độ + `loadingSequence`.
- Pin nằm trên `Package.isPinned`; version nằm ở `LoadPlan.planVersion`.
- Warehouse chỉ persist `LoadingExecution`; không có confirmation/deviation/unload entities.

Xem chi tiết từng sprint tại `sprint-2/README.md`, `sprint-3/README.md`, `sprint-4/README.md` và `sprint-5/README.md`.
