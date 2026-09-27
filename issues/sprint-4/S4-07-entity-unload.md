# S4-07 · Loại bỏ UnloadConfirmation khỏi persistence

| Field | Value |
|-------|-------|
| **Sprint** | 4 |
| **Module** | Driver Unloading |
| **Priority** | 🔴 Must |
| **Label** | `BE` |
| **Status** | 🔲 Todo — schema cleanup |

`refactor_entity.md` v3.4 không định nghĩa `UnloadConfirmation`; entity `CargoPackage` cũng đã được chuẩn hóa thành `Package`.

## Acceptance Criteria
- [ ] Không tạo/duy trì entity, repository hoặc migration `UnloadConfirmation`
- [ ] Driver đọc manifest từ `DeliveryStop → Order → Package`
- [ ] Chỉ cập nhật các field hiện hữu (`Trip.status`) và ghi `AuditLog` cho hành động hoàn tất
- [ ] Muốn theo dõi unload từng kiện/stop phải sửa data dictionary trước
