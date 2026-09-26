# S1-13 · Gán Role duy nhất cho User

| Field | Value |
|-------|-------|
| **Sprint** | 1 |
| **Module** | User |
| **Priority** | 🔴 Must |
| **Label** | `BE` |
| **PRD Ref** | FR-USER-02 |
| **Status** | 🔲 Todo |

## Mô tả
API thay role của User qua khóa ngoại `users.role_id`. Schema v3.4 quy định mỗi User có đúng một Role và không có bảng `UserRole`.

## Acceptance Criteria
- [ ] `PUT /api/users/{id}/role` — body `{ "roleId": 2 }` → thay role hiện tại
- [ ] Không hỗ trợ gỡ role thành null vì `users.role_id` là `NOT NULL`
- [ ] Validate: role phải tồn tại, user phải tồn tại
- [ ] Không được gán `SYSTEM_ADMIN` cho user thuộc company; chỉ System Admin được đổi role này
- [ ] Đồng bộ `userRoleType` với loại role nghiệp vụ theo rule của service

## Dependencies
- S1-12 (User CRUD)
